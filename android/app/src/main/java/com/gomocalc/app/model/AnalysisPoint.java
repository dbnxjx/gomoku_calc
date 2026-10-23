package com.gomocalc.app.model;

import java.util.List;
import java.util.Objects;

/** A candidate's score and win rate are always from black's perspective; null means unknown. */
public final class AnalysisPoint {
    public final BoardPoint point;
    public final Double score;
    public final Double blackWinRate;
    public final long visits;
    public final List<BoardPoint> principalVariation;

    public AnalysisPoint(BoardPoint point, Double score, Double blackWinRate, long visits,
                         List<BoardPoint> principalVariation) {
        if (visits < 0) throw new IllegalArgumentException("Visits must be non-negative");
        this.point = Objects.requireNonNull(point, "point");
        this.score = score;
        this.blackWinRate = AnalysisState.validWinRate(blackWinRate);
        this.visits = visits;
        this.principalVariation = AnalysisState.immutable(principalVariation);
    }
}
