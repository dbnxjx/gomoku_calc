package com.gomocalc.app.viewmodel;

import com.gomocalc.app.engine.AnalysisRequest;
import com.gomocalc.app.engine.EngineEvent;
import com.gomocalc.app.engine.EngineManager;
import com.gomocalc.app.engine.EngineOptions;
import com.gomocalc.app.engine.EngineType;
import com.gomocalc.app.engine.GomokuEngine;
import com.gomocalc.app.model.AnalysisState;

/** Native state coordinator. The current Capacitor page does not depend on this class yet. */
public final class GameViewModel implements AutoCloseable {
    private final EngineManager engineManager;
    private volatile AnalysisState state = AnalysisState.empty();
    private EngineOptions options = EngineOptions.defaults();

    public GameViewModel() { engineManager = new EngineManager(); }

    public synchronized void setEngine(EngineType type) { engineManager.select(type); }
    public synchronized void setOptions(EngineOptions options) {
        if (options != null) this.options = options;
    }
    public AnalysisState getState() { return state; }

    public void analyze(AnalysisRequest request) {
        final GomokuEngine engine = engineManager.getActive();
        if (engine == null) {
            state = AnalysisState.empty();
            return;
        }
        try {
            engine.initialize(options);
            engine.analyze(request, new GomokuEngine.Listener() {
                @Override public void onEvent(EngineEvent event) {
                    if (event.type == EngineEvent.Type.UPDATE || event.type == EngineEvent.Type.FINISHED) {
                        if (event.state != null) state = event.state;
                    }
                }
            });
        } catch (Exception error) {
            state = AnalysisState.empty();
        }
    }

    public void stop() {
        GomokuEngine engine = engineManager.getActive();
        if (engine != null) engine.stop();
    }

    @Override public void close() { engineManager.close(); }
}
