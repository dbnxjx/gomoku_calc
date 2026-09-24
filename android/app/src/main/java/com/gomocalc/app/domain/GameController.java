package com.gomocalc.app.domain;

import com.gomocalc.app.model.BoardPoint;

/** Future ViewModel entry point for rule-checked board editing and branch navigation. */
public interface GameController {
    BoardState currentPosition();
    MoveTree snapshot();
    void newGame(BoardState initialPosition);
    void restore(MoveTree tree);

    /** Uses the current side to move. A rejection leaves both board and tree unchanged. */
    RuleEngine.MoveVerdict play(BoardPoint point);

    /** Returns false at the root. */
    boolean undo();

    /** Selects one of the current node's direct children; returns false if it is not a child. */
    boolean redo(String childNodeId);
    void goTo(String nodeId);
    void setComment(String nodeId, String comment);
}
