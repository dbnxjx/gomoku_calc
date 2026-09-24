package com.gomocalc.app.model;

import java.util.Objects;

/** A forcing/winning point reported by the engine, with the benefiting side made explicit. */
public final class KillPoint {
    public final BoardPoint point;
    public final int side;
    public final int depth;
    public final String reason;

    public KillPoint(BoardPoint point, int side, int depth, String reason) {
        if (side != 1 && side != 2) throw new IllegalArgumentException("Side must be black=1 or white=2");
        if (depth < 0) throw new IllegalArgumentException("Depth must be non-negative");
        this.point = Objects.requireNonNull(point, "point");
        this.side = side;
        this.depth = depth;
        this.reason = reason;
    }
}
