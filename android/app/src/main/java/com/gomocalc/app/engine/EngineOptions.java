package com.gomocalc.app.engine;

import java.util.Objects;

/** Search settings only; presentation switches belong to DisplayOptions. Zero limits mean unlimited. */
public final class EngineOptions {
    public enum Rule { FREESTYLE, STANDARD, RENJU }

    public final Rule rule;
    public final int maxThreads;
    public final long timeLimitMs;
    public final long maxVisits;

    public EngineOptions(Rule rule, int maxThreads, long timeLimitMs, long maxVisits) {
        if (maxThreads < 1 || timeLimitMs < 0 || maxVisits < 0)
            throw new IllegalArgumentException("Invalid search limits");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.maxThreads = maxThreads;
        this.timeLimitMs = timeLimitMs;
        this.maxVisits = maxVisits;
    }

    public static EngineOptions defaults() {
        return new EngineOptions(Rule.FREESTYLE, 1, 1000L, 0L);
    }
}
