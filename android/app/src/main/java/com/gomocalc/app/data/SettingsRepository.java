package com.gomocalc.app.data;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Persistence only; ViewModel interprets display settings and engine adapters interpret engine settings. */
public interface SettingsRepository {
    /** Returns an empty snapshot when no settings have been saved. Run blocking I/O off the main thread. */
    Snapshot load() throws IOException;
    /** Atomically replaces the snapshot. Implementations preserve unknown keys when migrating formats. */
    void save(Snapshot settings) throws IOException;

    /** Namespaced keys (for example display.showAdvantage, rapfi.threads, katago.modelId). */
    final class Snapshot {
        public final int schemaVersion;
        public final Map<String, String> values;

        public Snapshot(int schemaVersion, Map<String, String> values) {
            if (schemaVersion < 1) throw new IllegalArgumentException("Invalid settings schema version");
            this.schemaVersion = schemaVersion;
            Map<String, String> copy = new LinkedHashMap<>(Objects.requireNonNull(values, "values"));
            for (Map.Entry<String, String> entry : copy.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "setting key");
                Objects.requireNonNull(entry.getValue(), "setting value");
            }
            this.values = Collections.unmodifiableMap(copy);
        }
    }
}
