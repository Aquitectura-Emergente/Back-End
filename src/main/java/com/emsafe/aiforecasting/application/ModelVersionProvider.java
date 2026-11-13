package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.ModelVersion;

/** Versión de modelo vigente. En la fase 3 se implementa leyendo las properties. */
@FunctionalInterface
public interface ModelVersionProvider {

    ModelVersion current();
}
