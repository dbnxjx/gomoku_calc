package com.gomocalc.app.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable branch snapshot. A future domain implementation validates tree connectivity. */
public final class MoveTree {
    public final BoardState initialPosition;
    public final String rootNodeId;
    public final String currentNodeId;
    public final List<Node> nodes;

    public MoveTree(BoardState initialPosition, String rootNodeId, String currentNodeId, List<Node> nodes) {
        this.initialPosition = Objects.requireNonNull(initialPosition, "initialPosition");
        this.rootNodeId = Objects.requireNonNull(rootNodeId, "rootNodeId");
        this.currentNodeId = Objects.requireNonNull(currentNodeId, "currentNodeId");
        List<Node> copy = new ArrayList<>(Objects.requireNonNull(nodes, "nodes"));
        for (Node node : copy) Objects.requireNonNull(node, "node");
        this.nodes = Collections.unmodifiableList(copy);
    }

    public static final class Node {
        public final String id;
        /** Null only for the root. */
        public final String parentId;
        /** Null only for the root; the initial position is stored on MoveTree. */
        public final Move move;
        /** Ordered branch IDs, with the main continuation first. */
        public final List<String> children;
        public final String comment;

        public Node(String id, String parentId, Move move, List<String> children, String comment) {
            this.id = Objects.requireNonNull(id, "id");
            this.parentId = parentId;
            this.move = move;
            List<String> copy = new ArrayList<>(Objects.requireNonNull(children, "children"));
            for (String child : copy) Objects.requireNonNull(child, "child ID");
            this.children = Collections.unmodifiableList(copy);
            this.comment = comment == null ? "" : comment;
        }
    }
}
