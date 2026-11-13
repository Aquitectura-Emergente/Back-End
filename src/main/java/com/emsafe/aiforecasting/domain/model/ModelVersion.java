package com.emsafe.aiforecasting.domain.model;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/** Versión del modelo de proyección que produjo una evaluación (p. ej. LINEAR_RATE v1). */
public record ModelVersion(String modelId, String version) {

    public ModelVersion {
        requireText(modelId, "modelId");
        requireText(version, "version");
    }

    /** Código compacto, p. ej. {@code LINEAR_RATE_V1}. */
    public String code() {
        return modelId + "_V" + version;
    }
}
