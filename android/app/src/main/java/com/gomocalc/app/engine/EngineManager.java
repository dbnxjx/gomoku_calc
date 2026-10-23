package com.gomocalc.app.engine;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Owns adapters. After passing a manager to GameViewModel, switch engines through the ViewModel. */
public final class EngineManager implements AutoCloseable {
    private final Map<EngineType, GomokuEngine> engines =
            new EnumMap<EngineType, GomokuEngine>(EngineType.class);
    private GomokuEngine active;
    private boolean closed;

    public EngineManager() {
        register(new RapfiEngineAdapter());
        register(new KataGoEngineAdapter());
    }

    /** Register before handing ownership to the ViewModel; duplicate adapters are rejected. */
    public synchronized void register(GomokuEngine engine) {
        ensureOpen();
        Objects.requireNonNull(engine, "engine");
        if (engines.containsKey(engine.getType()))
            throw new IllegalArgumentException("Engine already registered: " + engine.getType());
        engines.put(engine.getType(), engine);
    }

    /** Injection constructor for backend integration and contract tests. */
    public EngineManager(GomokuEngine rapfi, GomokuEngine kataGo) {
        register(rapfi);
        register(kataGo);
    }

    public synchronized GomokuEngine select(EngineType type) {
        ensureOpen();
        GomokuEngine next = engines.get(Objects.requireNonNull(type, "type"));
        if (next == null) throw new IllegalArgumentException("Engine not registered: " + type);
        if (active != next) {
            if (active != null) active.stop();
            active = next;
        }
        return active;
    }

    public synchronized GomokuEngine getActive() { return active; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (GomokuEngine engine : engines.values()) {
            try { engine.stop(); } catch (RuntimeException error) { failure = error; }
            try { engine.close(); } catch (RuntimeException error) { failure = error; }
        }
        engines.clear();
        active = null;
        if (failure != null) throw failure;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Engine manager is closed");
    }
}
