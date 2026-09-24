package com.gomocalc.app.engine.katago;

import com.gomocalc.app.engine.AnalysisRequest;
import com.gomocalc.app.engine.EngineOptions;
import com.gomocalc.app.engine.GomokuEngine;

/**
 * Boundary for the future KataGo native implementation.
 *
 * <p>KataGo transport, model loading, and worker lifecycle belong here.  It emits the same
 * application-level snapshots as Rapfi, leaving the UI and ViewModel independent of the backend.</p>
 */
public interface KataGoNativeBridge extends AutoCloseable {
    boolean isAvailable();

    void initialize(EngineOptions options) throws Exception;

    void analyze(AnalysisRequest request, GomokuEngine.Listener listener);

    void stop();

    @Override
    void close();
}
