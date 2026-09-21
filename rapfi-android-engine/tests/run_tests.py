#!/usr/bin/env python3
"""
Automated verification suite for the native Rapfi engine bridge.

Drives the `rapfi-cli` harness over stdin and asserts on the engine's stdout
protocol output, which is the exact format consumed by the web version's
src/ai/engine.js parser.

Usage:
    python3 run_tests.py [--cli /path/to/rapfi-cli]
"""

import argparse
import re
import subprocess
import sys

# ---------------------------------------------------------------------------
# Test cases: (name, command script, list of assertion functions)
# Each assertion receives the full list of engine "OUT ..." lines.
# ---------------------------------------------------------------------------


def run_cli(cli_path: str, script: str) -> list[str]:
    proc = subprocess.run(
        [cli_path],
        input=script,
        text=True,
        capture_output=True,
        timeout=120,
    )
    if proc.returncode != 0:
        raise AssertionError(
            f"cli exited with {proc.returncode}\nstdout:\n{proc.stdout}\nstderr:\n{proc.stderr}"
        )
    return [ln[4:] for ln in proc.stdout.splitlines() if ln.startswith("OUT ")]


def assert_start_ok(lines: list[str]):
    assert "OK" in lines, f"expected OK reply, got: {lines[:10]}"


def assert_info_and_bestmove(lines: list[str]):
    infos = [ln for ln in lines if ln.startswith("INFO ")]
    assert infos, "expected INFO ... search output"
    heads = {ln.split(" ", 2)[1] for ln in infos}
    for required in ("DEPTH", "EVAL", "BESTLINE", "NUMPV"):
        assert required in heads, f"missing INFO {required} in {sorted(heads)}"

    bestmove = [ln for ln in lines if re.fullmatch(r"\d+,\d+", ln)]
    assert bestmove, f"expected a final best move line 'x,y', got: {lines[-5:]}"


def assert_forbid(lines: list[str]):
    forbid = [ln for ln in lines if ln.startswith("FORBID")]
    assert forbid, f"expected FORBID output, got: {lines}"
    # The double-three point (7,7) must be reported as forbidden -> "0707"
    assert any("0707" in ln for ln in forbid), f"expected forbidden point 0707, got: {forbid}"


def assert_no_error(lines: list[str]):
    errors = [ln for ln in lines if ln.startswith("ERROR")]
    assert not errors, f"engine reported errors: {errors}"


TESTS = [
    (
        "init and START reply",
        "START 15\n",
        [assert_start_ok, assert_no_error],
    ),
    (
        "multi-PV analysis emits INFO + best move",
        "\n".join(
            [
                "START 15",
                "INFO RULE 0",
                "INFO THREAD_NUM 1",
                "INFO TIMEOUT_TURN 2000",
                "INFO MAX_DEPTH 8",
                "INFO SHOW_DETAIL 3",
                "YXBOARD 7,7,1 DONE",
                "YXNBEST 3",
                "",
            ]
        ),
        [assert_info_and_bestmove, assert_no_error],
    ),
    (
        "renju double-three forbidden point",
        "\n".join(
            [
                "START 15",
                "INFO RULE 2",
                "YXBOARD 6,6,1 0,0,2 8,8,1 1,0,2 6,8,1 2,0,2 8,6,1 3,0,2 DONE",
                "YXSHOWFORBID",
                "",
            ]
        ),
        [assert_forbid, assert_no_error],
    ),
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", default="build/rapfi-cli")
    args = parser.parse_args()

    passed = 0
    for name, script, asserts in TESTS:
        try:
            lines = run_cli(args.cli, script)
            for fn in asserts:
                fn(lines)
        except Exception as exc:  # noqa: BLE001 - report and continue
            print(f"[FAIL] {name}: {exc}")
            continue
        print(f"[PASS] {name}")
        passed += 1

    print(f"\n{passed}/{len(TESTS)} tests passed")
    return 0 if passed == len(TESTS) else 1


if __name__ == "__main__":
    sys.exit(main())
