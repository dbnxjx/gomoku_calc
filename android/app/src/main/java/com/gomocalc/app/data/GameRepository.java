package com.gomocalc.app.data;

import com.gomocalc.app.domain.MoveTree;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Storage contract only. Blocking methods must be called off the Android main thread. */
public interface GameRepository {
    /** Returns an immutable snapshot of available game metadata. */
    List<Summary> list() throws IOException;
    Optional<Record> load(String id) throws IOException;
    /** Atomically inserts or replaces a record under its stable ID. */
    void save(Record game) throws IOException;
    void delete(String id) throws IOException;

    final class Summary {
        public final String id;
        public final String title;
        public final long updatedAtEpochMs;

        public Summary(String id, String title, long updatedAtEpochMs) {
            this.id = Objects.requireNonNull(id, "id");
            this.title = Objects.requireNonNull(title, "title");
            this.updatedAtEpochMs = updatedAtEpochMs;
        }
    }

    final class Record {
        public final Summary summary;
        public final MoveTree tree;

        public Record(Summary summary, MoveTree tree) {
            this.summary = Objects.requireNonNull(summary, "summary");
            this.tree = Objects.requireNonNull(tree, "tree");
        }
    }
}
