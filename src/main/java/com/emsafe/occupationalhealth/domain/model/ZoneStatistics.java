package com.emsafe.occupationalhealth.domain.model;

import java.math.BigDecimal;

/**
 * Estadísticas agregadas de una zona en un período, tal como las devuelve
 * ZoneExposurePort.zoneStatistics() (informe §5.6.1).
 *
 * Deliberadamente sin ningún campo que identifique a una persona: es el tipo
 * que alimenta los reportes por zona, donde RF32 y RNF11 prohíben el detalle
 * individual.
 */
public record ZoneStatistics(
        Long zoneId,
        BigDecimal average,
        BigDecimal max,
        int readingCount) {

    public static ZoneStatistics empty(Long zoneId) {
        return new ZoneStatistics(zoneId, null, null, 0);
    }
}
