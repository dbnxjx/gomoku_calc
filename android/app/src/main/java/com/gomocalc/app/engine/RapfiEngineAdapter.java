package com.gomocalc.app.engine;

import com.gomocalc.app.engine.rapfi.RapfiNativeBridge;

/** Rapfi team's integration file. No JNI library or WebAssembly engine is loaded here. */
public final class RapfiEngineAdapter implements GomokuEngine {
    private static final String REASON = "Native Rapfi is not connected yet; browser WebAssembly Rapfi has been removed";
    private final RapfiNativeBridge bridge;

    /** Keeps the current unavailable stub until a native bridge is supplied. */
    public RapfiEngineAdapter() { this(null); }

    /** Injection point for the future Rapfi JNI/process implementation. */
    public RapfiEngineAdapter(RapfiNativeBridge bridge) { this.bridge = bridge; }

    @Override public EngineType getType() { return EngineType.RAPFI; }
    @Override public boolean isAvailable() { return bridge != null && bridge.isAvailable(); }
    @Override public String getUnavailableReason() { return isAvailable() ? null : REASON; }
    @Override public void initialize(EngineOptions options) {
        if (!isAvailable()) throw new UnsupportedOperationException(REASON);
        try {
            bridge.initialize(options);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize native Rapfi", error);
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
