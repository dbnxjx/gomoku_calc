package com.gomocalc.app.model;

import java.util.Objects;

/** Presentation-only switches. Toggling a layer must not restart or discard analysis. */
public final class DisplayOptions {
    public enum CandidateLabel { SCORE, WIN_RATE, VISITS }

    public final boolean showCandidates;
    public final boolean showKillPoints;
    public final boolean showHeatmap;
    /** Reserved for the top-left visit/s label on the Web board. */
    public final boolean showVisitsPerSecond;
    /** Reserved for the optional black/white advantage bar. */
    public final boolean showAdvantageBar;
    public final CandidateLabel candidateLabel;

    public DisplayOptions(boolean showCandidates, boolean showKillPoints, boolean showHeatmap,
                          boolean showVisitsPerSecond, boolean showAdvantageBar,
                          CandidateLabel candidateLabel) {
        this.showCandidates = showCandidates;
        this.showKillPoints = showKillPoints;
        this.showHeatmap = showHeatmap;
        this.showVisitsPerSecond = showVisitsPerSecond;
        this.showAdvantageBar = showAdvantageBar;
        this.candidateLabel = Objects.requireNonNull(candidateLabel, "candidateLabel");
    }

    public static DisplayOptions defaults() {
        return new DisplayOptions(true, true, true, true, true, CandidateLabel.SCORE);
    }

    public DisplayOptions withAdvantageBar(boolean visible) {
        return new DisplayOptions(showCandidates, showKillPoints, showHeatmap,
                showVisitsPerSecond, visible, candidateLabel);
    }
}
