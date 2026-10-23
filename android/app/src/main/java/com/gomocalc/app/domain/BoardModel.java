package com.gomocalc.app.domain;

/** Owns board/tree state. Future implementations enforce structure; RuleEngine owns game legality. */
public interface BoardModel {
    BoardState currentPosition();
    MoveTree snapshot();

    /** Atomically replaces the complete tree after validating its structure. */
    void restore(MoveTree tree);

    /** Appends a move already approved by RuleEngine and returns its stable branch node ID. */
    String append(Move move);

    /** Selects an existing node; throws IllegalArgumentException for an unknown ID. */
    void select(String nodeId);
    void setComment(String nodeId, String comment);
}
