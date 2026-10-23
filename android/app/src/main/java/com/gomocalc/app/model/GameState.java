package com.gomocalc.app.model;

import com.gomocalc.app.engine.EngineType;
import java.util.Objects;

/** Single observable presentation snapshot for a future bridge; the Web UI does not consume it yet. */
public final class GameState {
    public enum Phase { IDLE, INITIALIZING, ANALYZING, FINISHED, STOPPED, UNAVAILABLE, ERROR, CLOSED }

    public final EngineType engine;
    public final long positionRevision;
    public final Phase phase;
    public final AnalysisState analysis;
    public final DisplayOptions display;
    public final String message;

    public GameState(EngineType engine, long positionRevision, Phase phase,
                     AnalysisState analysis, DisplayOptions display, String message) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.positionRevision = positionRevision;
        this.phase = Objects.requireNonNull(phase, "phase");
        this.analysis = Objects.requireNonNull(analysis, "analysis");
        this.display = Objects.requireNonNull(display, "display");
        this.message = message;
    }

    public boolean isAnalyzing() { return phase == Phase.INITIALIZING || phase == Phase.ANALYZING; }
}
