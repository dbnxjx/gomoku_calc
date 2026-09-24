package com.gomocalc.app.model;

/** Zero-based coordinates: x increases rightward, y downward, matching the Web board. */
public final class BoardPoint {
    public final int x;
    public final int y;

    public BoardPoint(int x, int y) {
        if (x < 0 || y < 0) throw new IllegalArgumentException("Coordinates must be non-negative");
        this.x = x;
        this.y = y;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof BoardPoint)) return false;
        BoardPoint point = (BoardPoint) other;
        return x == point.x && y == point.y;
    }
    @Override public int hashCode() { return 31 * x + y; }
}
