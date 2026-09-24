package com.gomocalc.app.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable position exchanged between the domain, presentation, and storage layers. */
public final class BoardState {
    public final int boardSize;
    /** Row-major cells at y * boardSize + x: 0 = empty, 1 = black, 2 = white. */
    public final List<Integer> cells;
    public final int sideToMove;
    /** Canonical rule identity including variant, opening rules, and their configuration. */
    public final String rulesId;
    /** Ordered placements on this branch, preserving history-dependent opening rules. */
    public final List<Move> history;

    public BoardState(int boardSize, List<Integer> cells, int sideToMove,
                      String rulesId, List<Move> history) {
        if (boardSize < 1 || (long) boardSize * boardSize != Objects.requireNonNull(cells, "cells").size()) {
            throw new IllegalArgumentException("Cells must contain a complete square board");
        }
        if (sideToMove != 1 && sideToMove != 2) throw new IllegalArgumentException("Invalid side to move");
        if (Objects.requireNonNull(rulesId, "rulesId").trim().isEmpty()) {
            throw new IllegalArgumentException("A complete rule identity is required");
        }
        List<Integer> cellCopy = new ArrayList<>(cells);
        for (Integer cell : cellCopy) {
            if (cell == null || cell < 0 || cell > 2) throw new IllegalArgumentException("Invalid board cell");
        }
        List<Move> historyCopy = new ArrayList<>(Objects.requireNonNull(history, "history"));
        for (Move move : historyCopy) {
            Objects.requireNonNull(move, "history move");
            if (move.point.x < 0 || move.point.y < 0 || move.point.x >= boardSize || move.point.y >= boardSize) {
                throw new IllegalArgumentException("History move is outside the board");
            }
        }
        this.boardSize = boardSize;
        this.cells = Collections.unmodifiableList(cellCopy);
        this.sideToMove = sideToMove;
        this.rulesId = rulesId;
        this.history = Collections.unmodifiableList(historyCopy);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof BoardState)) return false;
        BoardState state = (BoardState) other;
        return boardSize == state.boardSize && sideToMove == state.sideToMove
                && cells.equals(state.cells) && rulesId.equals(state.rulesId) && history.equals(state.history);
    }

    @Override public int hashCode() { return Objects.hash(boardSize, cells, sideToMove, rulesId, history); }
}
