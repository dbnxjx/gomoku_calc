package com.gomocalc.app.data;

import com.gomocalc.app.domain.BoardState;
import com.gomocalc.app.engine.EngineType;
import java.util.Objects;

/** Full analysis identity; implementations must never key cached results by cells alone. */
public final class AnalysisCacheKey {
    /** Includes dimensions, cells, turn, rule configuration, and move history. */
    public final BoardState position;
    public final EngineType engineType;
    /** Engine version/build identity, not merely its display name. */
    public final String engineBuildId;
    /** Content/version fingerprint of the model/weights; use an explicit value for no model. */
    public final String modelId;
    /** Canonical fingerprint of every effective search option, including budget and protocol version. */
    public final String configurationId;

    public AnalysisCacheKey(BoardState position, EngineType engineType, String engineBuildId,
                            String modelId, String configurationId) {
        this.position = Objects.requireNonNull(position, "position");
        this.engineType = Objects.requireNonNull(engineType, "engineType");
        this.engineBuildId = requireIdentity(engineBuildId, "engineBuildId");
        this.modelId = requireIdentity(modelId, "modelId");
        this.configurationId = requireIdentity(configurationId, "configurationId");
    }

    private static String requireIdentity(String value, String name) {
        if (Objects.requireNonNull(value, name).trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must identify the effective configuration");
        }
        return value;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof AnalysisCacheKey)) return false;
        AnalysisCacheKey key = (AnalysisCacheKey) other;
        return position.equals(key.position) && engineType == key.engineType
                && engineBuildId.equals(key.engineBuildId) && modelId.equals(key.modelId)
                && configurationId.equals(key.configurationId);
    }

    @Override public int hashCode() {
        return Objects.hash(position, engineType, engineBuildId, modelId, configurationId);
    }
}
