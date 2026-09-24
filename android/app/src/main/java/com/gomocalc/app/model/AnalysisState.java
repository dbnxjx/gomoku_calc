package com.gomocalc.app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable complete analysis snapshot. Scores and win probabilities use BLACK's perspective.
 * No result means null probability, never an invented 50%. Counters start at zero. visits and
 * nodes are distinct: an adapter must not silently relabel node/s as visit/s.
 */
public final class AnalysisState {
    public final int depth;
    public final Double score;
    public final Double blackWinRate;
    public final long nodes;
    public final long visits;
    public final double visitsPerSecond;
    public final List<AnalysisPoint> candidates;
    public final List<HeatmapPoint> heatmap;
    public final List<KillPoint> killPoints;
    public final List<BoardPoint> principalVariation;
    public final List<BoardPoint> forbiddenPoints;
    public final BoardPoint bestMove;

    private AnalysisState(Builder value) {
        if (value.depth < 0 || value.nodes < 0 || value.visits < 0
                || !Double.isFinite(value.visitsPerSecond) || value.visitsPerSecond < 0)
            throw new IllegalArgumentException("Invalid analysis counters");
        depth = value.depth;
        score = value.score;
        blackWinRate = validWinRate(value.blackWinRate);
        nodes = value.nodes;
        visits = value.visits;
        visitsPerSecond = value.visitsPerSecond;
        candidates = immutable(value.candidates);
        heatmap = immutable(value.heatmap);
        killPoints = immutable(value.killPoints);
        principalVariation = immutable(value.principalVariation);
        forbiddenPoints = immutable(value.forbiddenPoints);
        bestMove = value.bestMove;
    }

    public static AnalysisState empty() { return new Builder().build(); }

    static Double validWinRate(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 1))
            throw new IllegalArgumentException("Black win probability must be in [0,1] or null");
        return value;
    }

    static <T> List<T> immutable(List<T> value) {
        if (value == null) return Collections.emptyList();
        List<T> copy = new ArrayList<T>(value);
        for (T item : copy) Objects.requireNonNull(item, "Snapshot entries cannot be null");
        return Collections.unmodifiableList(copy);
    }

    public static final class Builder {
        private int depth;
        private Double score;
        private Double blackWinRate;
        private long nodes;
        private long visits;
        private double visitsPerSecond;
        private List<AnalysisPoint> candidates;
        private List<HeatmapPoint> heatmap;
        private List<KillPoint> killPoints;
        private List<BoardPoint> principalVariation;
        private List<BoardPoint> forbiddenPoints;
        private BoardPoint bestMove;

        public Builder depth(int value) { depth = value; return this; }
        public Builder score(Double value) { score = value; return this; }
        public Builder blackWinRate(Double value) { blackWinRate = value; return this; }
        public Builder nodes(long value) { nodes = value; return this; }
        public Builder visits(long value) { visits = value; return this; }
        public Builder visitsPerSecond(double value) { visitsPerSecond = value; return this; }
        public Builder candidates(List<AnalysisPoint> value) { candidates = value; return this; }
        public Builder heatmap(List<HeatmapPoint> value) { heatmap = value; return this; }
        public Builder killPoints(List<KillPoint> value) { killPoints = value; return this; }
        public Builder principalVariation(List<BoardPoint> value) { principalVariation = value; return this; }
        public Builder forbiddenPoints(List<BoardPoint> value) { forbiddenPoints = value; return this; }
        public Builder bestMove(BoardPoint value) { bestMove = value; return this; }
        public AnalysisState build() { return new AnalysisState(this); }
    }
}
