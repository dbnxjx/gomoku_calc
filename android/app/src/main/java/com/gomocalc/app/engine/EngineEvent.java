package com.gomocalc.app.engine;

import com.gomocalc.app.model.AnalysisState;
import java.util.Objects;

/** One full snapshot per update; partial protocol records must be merged inside the adapter. */
public final class EngineEvent {
    public enum Type { STARTED, UPDATE, FINISHED, STOPPED, UNAVAILABLE, ERROR }

    public final Type type;
    public final AnalysisState state;
    public final String message;

    private EngineEvent(Type type, AnalysisState state, String message) {
        this.type = Objects.requireNonNull(type, "type");
        this.state = state;
        this.message = message;
    }

    public static EngineEvent of(Type type, AnalysisState state) {
        if (type == Type.ERROR || type == Type.UNAVAILABLE)
            throw new IllegalArgumentException("Use error() or unavailable() for failures");
        if (type == Type.UPDATE && state == null)
            throw new IllegalArgumentException("UPDATE needs an analysis snapshot");
        return new EngineEvent(type, state, null);
    }

    public static EngineEvent error(String message) {
        return new EngineEvent(Type.ERROR, null, Objects.requireNonNull(message, "message"));
    }

    public static EngineEvent unavailable(String message) {
        return new EngineEvent(Type.UNAVAILABLE, null, Objects.requireNonNull(message, "message"));
    }
}
