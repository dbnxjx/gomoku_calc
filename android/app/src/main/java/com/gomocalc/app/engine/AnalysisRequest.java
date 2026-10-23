package com.gomocalc.app.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable position. Cells are row-major (y * boardSize + x): 0 empty, 1 black, 2 white. */
public final class AnalysisRequest {
    public final int boardSize;
    public final List<Integer> board;
    public final int sideToMove;
    public final EngineOptions options;

    public AnalysisRequest(int boardSize, List<Integer> board, int sideToMove,
                           EngineOptions options) {
        if (boardSize < 1 || boardSize > 100 || board == null
                || board.size() != boardSize * boardSize)
            throw new IllegalArgumentException("A complete square board is required");
        if (sideToMove != 1 && sideToMove != 2)
            throw new IllegalArgumentException("sideToMove must be 1 (black) or 2 (white)");
        List<Integer> copy = new ArrayList<Integer>(board);
        for (Integer cell : copy)
            if (cell == null || cell < 0 || cell > 2)
                throw new IllegalArgumentException("Board cells must be 0, 1 or 2");
        this.boardSize = boardSize;
        this.board = Collections.unmodifiableList(copy);
        this.sideToMove = sideToMove;
        this.options = Objects.requireNonNull(options, "options");
    }

    public static AnalysisRequest empty(int boardSize, EngineOptions options) {
        if (boardSize < 1 || boardSize > 100)
            throw new IllegalArgumentException("Invalid board size");
        return new AnalysisRequest(boardSize, Collections.nCopies(boardSize * boardSize, 0), 1, options);
    }
}
