import argparse
import csv
import json
import os
import struct
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort


ROOT = Path(__file__).resolve().parent
BOARD_SIZE = 15
SPATIAL_CHANNELS = 22
GLOBAL_FEATURES = 19
OUTPUT_NAMES = [
    "policy",
    "value",
    "miscvalue",
    "moremiscvalue",
    "ownership",
    "scoring",
    "futurepos",
    "seki",
    "scorebelief",
]


def load_inputs(path):
    with path.open("rb") as f:
        header = f.read(4)
        if len(header) != 4:
            raise ValueError(f"Invalid input file: {path}")
        count = struct.unpack("<I", header)[0]
        spatial_size = SPATIAL_CHANNELS * BOARD_SIZE * BOARD_SIZE
        record_size = spatial_size + GLOBAL_FEATURES
        values = np.fromfile(f, dtype="<f4")
    expected = count * record_size
    if values.size != expected:
        raise ValueError(f"Input payload has {values.size} floats, expected {expected}")
    records = values.reshape(count, record_size)
    spatial = records[:, :spatial_size].reshape(count, SPATIAL_CHANNELS, BOARD_SIZE, BOARD_SIZE)
    global_input = records[:, spatial_size:]
    if not np.isfinite(records).all():
        raise ValueError("Input features contain NaN or infinity")
    with (ROOT / "onnx_eval_positions.csv").open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    if len(rows) != count:
        raise ValueError(f"Metadata has {len(rows)} positions, input header says {count}")
    return spatial, global_input, rows


def softmax(x):
    x = x.astype(np.float64)
    x = x - np.max(x, axis=-1, keepdims=True)
    e = np.exp(x)
    return e / np.sum(e, axis=-1, keepdims=True)


def run_batched(session, spatial, global_input, batch_size, dtype, keep_outputs=False):
    outputs = {name: [] for name in OUTPUT_NAMES} if keep_outputs else None
    warmup_end = min(batch_size, len(spatial))
    session.run(
        OUTPUT_NAMES,
        {
            "bin_input": spatial[:warmup_end].astype(dtype, copy=False),
            "global_input": global_input[:warmup_end].astype(dtype, copy=False),
        },
    )
    start = time.perf_counter()
    for begin in range(0, len(spatial), batch_size):
        end = min(begin + batch_size, len(spatial))
        feed = {
            "bin_input": spatial[begin:end].astype(dtype, copy=False),
            "global_input": global_input[begin:end].astype(dtype, copy=False),
        }
        result = session.run(OUTPUT_NAMES, feed)
        if any(not np.isfinite(value).all() for value in result):
            raise FloatingPointError(f"Model returned NaN or infinity for samples {begin}..{end - 1}")
        if keep_outputs:
            for name, value in zip(OUTPUT_NAMES, result):
                outputs[name].append(value.astype(np.float32, copy=False))
    elapsed = time.perf_counter() - start
    if keep_outputs:
        outputs = {name: np.concatenate(values, axis=0) for name, values in outputs.items()}
    return elapsed, outputs


def summarize_outputs(reference, candidate, rows):
    summary = {}
    for name in OUTPUT_NAMES:
        delta = np.abs(reference[name] - candidate[name])
        summary[name] = {
            "mae": float(np.mean(delta)),
            "p99_abs": float(np.percentile(delta, 99)),
            "max_abs": float(np.max(delta)),
        }

    ref_policy = reference["policy"]
    cand_policy = candidate["policy"]
    ref_probs = softmax(ref_policy)
    cand_probs = softmax(cand_policy)
    summary["policy_probability_mae"] = float(np.mean(np.abs(ref_probs - cand_probs)))
    summary["policy_top1_match_all_heads"] = float(
        np.mean(np.argmax(ref_policy, axis=-1) == np.argmax(cand_policy, axis=-1))
    )
    summary["policy_top1_match_head0"] = float(
        np.mean(np.argmax(ref_policy[:, 0], axis=-1) == np.argmax(cand_policy[:, 0], axis=-1))
    )
    summary["value_probability_mae"] = float(
        np.mean(np.abs(softmax(reference["value"]) - softmax(candidate["value"])))
    )
    summary["policy_top1_match_head0_by_ply"] = {}
    for ply in sorted({int(row["ply"]) for row in rows}):
        indices = [i for i, row in enumerate(rows) if int(row["ply"]) == ply]
        matches = np.argmax(ref_policy[indices, 0], axis=-1) == np.argmax(cand_policy[indices, 0], axis=-1)
        summary["policy_top1_match_head0_by_ply"][str(ply)] = float(np.mean(matches))
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--threads", type=int, default=min(8, os.cpu_count() or 1))
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--output", type=Path, default=ROOT / "onnx_quantization_results.json")
    args = parser.parse_args()

    spatial, global_input, rows = load_inputs(ROOT / "onnx_eval_inputs.bin")
    model_paths = {
        "fp32": ROOT / "kata1-b28c512nbt-s12043015936-d5616446734.fp32.onnx",
        "fp16": ROOT / "kata1-b28c512nbt-s12043015936-d5616446734.fp16.onnx",
        "uint8": ROOT / "kata1-b28c512nbt-s12043015936-d5616446734.uint8.onnx",
    }
    options = ort.SessionOptions()
    options.intra_op_num_threads = args.threads
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

    results = {
        "runtime": {
            "onnxruntime": ort.__version__,
            "execution_provider": "CPUExecutionProvider",
            "available_providers": ort.get_available_providers(),
            "threads": args.threads,
            "warmup_batches_per_batch_size": 1,
        },
        "positions": {
            "count": len(rows),
            "board_size": BOARD_SIZE,
            "renju_forbidden_features": True,
            "sample_plies": sorted({int(row["ply"]) for row in rows}),
            "games": len({row["game"] for row in rows}),
        },
        "models": {},
    }
    reference = None
    for name, path in model_paths.items():
        print(f"Loading {name}: {path.name}", flush=True)
        session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
        input_types = {meta.name: meta.type for meta in session.get_inputs()}
        dtype = np.float16 if "float16" in input_types["bin_input"] else np.float32
        for input_name in ("bin_input", "global_input"):
            if input_name not in input_types:
                raise ValueError(f"{path.name} is missing input {input_name}")
        output_names = {meta.name for meta in session.get_outputs()}
        if not set(OUTPUT_NAMES).issubset(output_names):
            raise ValueError(f"{path.name} missing outputs: {sorted(set(OUTPUT_NAMES) - output_names)}")

        elapsed1, outputs = run_batched(session, spatial, global_input, 1, dtype, keep_outputs=True)
        elapsed_batch, _ = run_batched(session, spatial, global_input, args.batch_size, dtype, keep_outputs=False)
        results["models"][name] = {
            "file_size_bytes": path.stat().st_size,
            "input_type": str(dtype),
            "batch1_seconds": elapsed1,
            "batch1_nn_eval_per_second": len(rows) / elapsed1,
            "batch_size": args.batch_size,
            "batch_seconds": elapsed_batch,
            "batch_nn_eval_per_second": len(rows) / elapsed_batch,
        }
        if name == "fp32":
            reference = outputs
        else:
            results["models"][name]["difference_vs_fp32"] = summarize_outputs(reference, outputs, rows)
        print(
            f"  batch1={results['models'][name]['batch1_nn_eval_per_second']:.3f} NN eval/s; "
            f"batch{args.batch_size}={results['models'][name]['batch_nn_eval_per_second']:.3f} NN eval/s",
            flush=True,
        )
        del outputs, session

    args.output.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
