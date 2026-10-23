package com.gomocalc.app.model;

import java.util.Objects;

/** One evaluated intersection: black's win probability [0,1] (null if unknown) and visits. */
public final class HeatmapPoint {
    public final BoardPoint point;
    public final Double blackWinRate;
    public final long visits;

    public HeatmapPoint(BoardPoint point, Double blackWinRate, long visits) {
        if (visits < 0) throw new IllegalArgumentException("Visits must be non-negative");
        this.point = Objects.requireNonNull(point, "point");
        this.blackWinRate = AnalysisState.validWinRate(blackWinRate);
        this.visits = visits;
    }
}
