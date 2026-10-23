package com.gomocalc.app.engine;

/**
 * Native integration boundary. Commands must return promptly: use an adapter-owned worker for
 * expensive work. stop/close must never wait for a listener callback to finish. initialize must
 * be idempotent for the same options. Callbacks may come from any thread, including synchronously.
 * Each analyze call owns its listener; never send an older search's events to a newer listener.
 */
public interface GomokuEngine extends AutoCloseable {
    EngineType getType();
    boolean isAvailable();
    /** Non-null explanation while unavailable; null when a real backend can be used. */
    String getUnavailableReason();
    void initialize(EngineOptions options) throws Exception;
    void analyze(AnalysisRequest request, Listener listener);
    void stop();
    @Override void close();

    interface Listener {
        void onEvent(EngineEvent event);
    }
}
