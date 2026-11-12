package com.emsafe.aiforecasting.domain.model;

/**
 * Método y unidad de exposición que este contexto sabe proyectar: el índice magnético
 * acumulado de Personal Dosimetry, en uT·s. No es una "dosis".
 */
public final class ExposureMethod {

    public static final String MAGNETIC_TIME_INDEX_V1 = "MAGNETIC_TIME_INDEX_V1";
    public static final String MICROTESLA_SECONDS = "uT*s";

    private ExposureMethod() {
    }

    public static void ensureSupported(String methodId, String unit) {
        if (!MAGNETIC_TIME_INDEX_V1.equals(methodId)) {
            throw ForecastingRuleException.invalid("unsupported exposure method " + methodId
                    + "; expected " + MAGNETIC_TIME_INDEX_V1);
        }
        if (!MICROTESLA_SECONDS.equals(unit)) {
            throw ForecastingRuleException.invalid("unsupported unit " + unit + "; expected " + MICROTESLA_SECONDS);
        }
    }
}
