package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.ModelVersionProvider;
import com.emsafe.aiforecasting.domain.model.ModelVersion;

/** Lee {@code model-id} y {@code model-version} de {@link ForecastingProperties}. */
public class PropertiesModelVersionProvider implements ModelVersionProvider {

    private final ModelVersion modelVersion;

    public PropertiesModelVersionProvider(ForecastingProperties properties) {
        this.modelVersion = new ModelVersion(properties.getModelId(), properties.getModelVersion());
    }

    @Override
    public ModelVersion current() {
        return modelVersion;
    }
}
