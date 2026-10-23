package com.gomocalc.app.domain;

import com.gomocalc.app.model.BoardPoint;
import java.util.List;
import java.util.Objects;

/** Pure rule checks using BoardState.rulesId; no search engine or UI dependencies. */
public interface RuleEngine {
    MoveVerdict evaluateMove(BoardState position, Move move);
    Outcome evaluatePosition(BoardState position);

    /** Returns an immutable snapshot of forbidden points for the side to move. */
    List<BoardPoint> forbiddenPoints(BoardState position);

    enum Outcome { IN_PROGRESS, BLACK_WIN, WHITE_WIN, DRAW }
    enum Rejection { NONE, OUT_OF_BOUNDS, OCCUPIED, WRONG_TURN, FORBIDDEN, GAME_FINISHED, UNSUPPORTED_RULES }

    final class MoveVerdict {
        public final Rejection rejection;
        public final String reason;

        public MoveVerdict(Rejection rejection, String reason) {
            this.rejection = Objects.requireNonNull(rejection, "rejection");
            this.reason = reason == null ? "" : reason;
        }

        public boolean isLegal() { return rejection == Rejection.NONE; }
    }
}
