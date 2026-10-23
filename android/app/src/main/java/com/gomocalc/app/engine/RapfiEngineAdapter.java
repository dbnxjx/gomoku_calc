package com.gomocalc.app.engine;

import com.gomocalc.app.engine.rapfi.JniRapfiNativeBridge;
import com.gomocalc.app.engine.rapfi.RapfiNativeBridge;

/** Rapfi adapter. Delegates to a native bridge that runs the in-process Rapfi engine over JNI. */
public final class RapfiEngineAdapter implements GomokuEngine {
    private static final String REASON = "Native Rapfi .so is unavailable for this ABI";
    private final RapfiNativeBridge bridge;

    /** Wires the real JNI bridge; degrades to unavailable when the .so is missing. */
    public RapfiEngineAdapter() { this(new JniRapfiNativeBridge()); }

    /** Injection point for tests and alternative Rapfi transport implementations. */
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
