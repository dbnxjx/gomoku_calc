package com.gomocalc.app.engine.rapfi;

import com.gomocalc.app.engine.AnalysisRequest;
import com.gomocalc.app.engine.EngineOptions;
import com.gomocalc.app.engine.GomokuEngine;

/**
 * Boundary for the future Rapfi native implementation.
 *
 * <p>The bridge owns JNI/process details and its worker threads.  The application layer only sees
 * the stable {@link GomokuEngine.Listener} event stream, so the web page and ViewModel do not need
 * to know whether Rapfi is linked through JNI or a local process.</p>
 */
public interface RapfiNativeBridge extends AutoCloseable {
    boolean isAvailable();

    void initialize(EngineOptions options) throws Exception;

    void analyze(AnalysisRequest request, GomokuEngine.Listener listener);

    void stop();

    @Override
    void close();
}
