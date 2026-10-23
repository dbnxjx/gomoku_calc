package com.gomocalc.app.domain;

import com.gomocalc.app.model.BoardPoint;
import java.util.Objects;

/** A placement in board coordinates: x grows right, y grows down, both start at zero. */
public final class Move {
    public final BoardPoint point;
    /** 1 = black, 2 = white. */
    public final int color;

    public Move(BoardPoint point, int color) {
        this.point = Objects.requireNonNull(point, "point");
        if (color != 1 && color != 2) throw new IllegalArgumentException("Invalid stone color");
        this.color = color;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof Move)) return false;
        Move move = (Move) other;
        return color == move.color && point.x == move.point.x && point.y == move.point.y;
    }

    @Override public int hashCode() { return Objects.hash(point.x, point.y, color); }
}
