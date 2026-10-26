package com.gomocalc.app.engine.rapfi;

import com.gomocalc.app.engine.AnalysisRequest;
import com.gomocalc.app.engine.EngineEvent;
import com.gomocalc.app.engine.EngineOptions;
import com.gomocalc.app.engine.GomokuEngine;
import com.gomocalc.app.model.AnalysisState;

import java.util.ArrayList;
import java.util.List;

/**
 * JNI-backed {@link RapfiNativeBridge}.
 *
 * <p>Owns the in-process {@link RapfiJni} engine, builds the Rapfi command stream from
 * {@link AnalysisRequest} and merges raw stdout lines into {@link EngineEvent} snapshots via
 * {@link RapfiAnalyzer}. Callbacks may arrive on engine worker threads and are forwarded to the
 * currently active listener.</p>
 */
public final class JniRapfiNativeBridge implements RapfiNativeBridge, RapfiJni.Listener {

    /** Multi-PV width used to expose candidate moves to {@link AnalysisState}. */
    private static final int DEFAULT_MULTI_PV = 3;

    private final RapfiJni jni;
    private volatile RapfiAnalyzer analyzer;
    private volatile GomokuEngine.Listener listener;
    private volatile int boardSize = -1;

    public JniRapfiNativeBridge() {
        this.jni = new RapfiJni(this);
    }

    @Override
    public boolean isAvailable() {
        return RapfiJni.isLibraryAvailable();
    }

    @Override
    public void initialize(EngineOptions options) throws Exception {
        jni.start(null);   // built-in classical evaluation (self-contained)
        sendInfo(options);
    }

    @Override
    public void analyze(AnalysisRequest request, GomokuEngine.Listener target) {
        if (!isAvailable()) {
            if (target != null) target.onEvent(EngineEvent.unavailable("Native Rapfi .so is unavailable"));
            return;
        }

        // Each analyze call owns its listener; stop any in-flight search first.
        jni.stopThinking();

        RapfiAnalyzer next = new RapfiAnalyzer(request.sideToMove);
        this.analyzer = next;
        this.listener = target;

        if (target != null)
            target.onEvent(EngineEvent.of(EngineEvent.Type.STARTED, AnalysisState.empty()));

        if (boardSize != request.boardSize) {
            boardSize = request.boardSize;
            jni.sendCommand(RapfiProtocol.START + " " + request.boardSize);
        }

        sendInfo(request.options);
        jni.sendCommand(buildBoardCommand(request));
        jni.sendCommand(RapfiProtocol.YXNBEST + " " + DEFAULT_MULTI_PV);
    }

    @Override
    public void stop() {
        jni.stopThinking();
    }

    @Override
    public void close() {
        jni.stopThinking();
        jni.saveDatabase();   // persist cached analysis records before tearing down
        jni.close();
        analyzer = null;
        listener = null;
    }

    @Override
    public void onStdout(String line) {
        RapfiAnalyzer active = analyzer;
        GomokuEngine.Listener target = listener;
        if (active == null || target == null)
            return;
        // Search output can arrive on an engine worker thread while the command
        // thread still emits synchronous replies; serialize per-analysis merging.
        EngineEvent event;
        synchronized (active) {
            event = active.onLine(line);
        }
        if (event != null)
            target.onEvent(event);
    }

    private void sendInfo(EngineOptions options) {
        jni.sendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_RULE + " "
                + RapfiProtocol.ruleId(options.rule));
        jni.sendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_THREAD_NUM + " "
                + Math.max(1, options.maxThreads));
        jni.sendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_TIMEOUT_TURN + " "
                + options.timeLimitMs);
        jni.sendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_MAX_NODE + " "
                + options.maxVisits);
        jni.sendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_SHOW_DETAIL + " 3");
        jni.sendCommand(RapfiProtocol.INFO + " " + RapfiProtocol.INFO_PONDERING + " 0");
    }

    /**
     * Rebuilds a Rapfi {@code YXBOARD ... DONE} command from the row-major board grid.
     * Rapfi expects an ordered, alternating black/white move sequence (black moves first); the
     * final position is order-independent, so stones of each colour are emitted in row-major
     * order while preserving the correct black-white alternation.
     */
    private static String buildBoardCommand(AnalysisRequest request) {
        int size = request.boardSize;
        List<int[]> black = new ArrayList<>();
        List<int[]> white = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = request.board.get(y * size + x);
                if (v == 1) black.add(new int[] {x, y});
                else if (v == 2) white.add(new int[] {x, y});
            }
        }

        StringBuilder sb = new StringBuilder(RapfiProtocol.YXBOARD);
        int bi = 0, wi = 0;
        while (bi < black.size() || wi < white.size()) {
            if (bi <= wi && bi < black.size()) {
                appendMove(sb, black.get(bi++), 1);
            } else if (wi < white.size()) {
                appendMove(sb, white.get(wi++), 2);
            } else {
                appendMove(sb, black.get(bi++), 1);
            }
        }
        sb.append(' ').append(RapfiProtocol.DONE);
        return sb.toString();
    }

    private static void appendMove(StringBuilder sb, int[] p, int color) {
        sb.append(' ').append(p[0]).append(',').append(p[1]).append(',').append(color);
    }
}
