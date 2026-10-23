package com.gomocalc.app.data;

import com.gomocalc.app.model.AnalysisState;
import java.io.IOException;
import java.util.Optional;

/** Optional analysis storage. A cache miss always allows the engine to compute a fresh result. */
public interface AnalysisCacheRepository {
    /** Blocking I/O runs off the main thread. Results must be immutable snapshots. */
    Optional<AnalysisState> load(AnalysisCacheKey key) throws IOException;

    /** Saves a completed, successful result only; never stores a running or failed analysis. */
    void save(AnalysisCacheKey key, AnalysisState result) throws IOException;
    void delete(AnalysisCacheKey key) throws IOException;
    void clear() throws IOException;
}
