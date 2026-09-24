package com.gomocalc.app.engine;

import com.gomocalc.app.engine.katago.KataGoNativeBridge;

/**
 * KataGo team's integration file. Future transport/protocol parsing belongs inside this adapter.
 * Normalize every point's win rate to black's perspective and publish its visits plus visit/s.
 * A backend/model that supports this board game is required; no Go-to-Gomoku conversion is assumed.
 */
public final class KataGoEngineAdapter implements GomokuEngine {
    private static final String REASON = "Native KataGo transport and model are not implemented";
    private final KataGoNativeBridge bridge;

    /** Keeps the current unavailable stub until a native bridge is supplied. */
    public KataGoEngineAdapter() { this(null); }

    /** Injection point for the future KataGo native transport/model implementation. */
    public KataGoEngineAdapter(KataGoNativeBridge bridge) { this.bridge = bridge; }

    @Override public EngineType getType() { return EngineType.KATAGO; }
    @Override public boolean isAvailable() { return bridge != null && bridge.isAvailable(); }
    @Override public String getUnavailableReason() { return isAvailable() ? null : REASON; }
    @Override public void initialize(EngineOptions options) {
        if (!isAvailable()) throw new UnsupportedOperationException(REASON);
        try {
            bridge.initialize(options);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize native KataGo", error);
        }
    }
    @Override public void analyze(AnalysisRequest request, Listener listener) {
        if (!isAvailable()) {
            if (listener != null) listener.onEvent(EngineEvent.unavailable(REASON));
            return;
        }
        bridge.analyze(request, listener);
    }
    @Override public void stop() { if (bridge != null) bridge.stop(); }
    @Override public void close() { if (bridge != null) bridge.close(); }
}
