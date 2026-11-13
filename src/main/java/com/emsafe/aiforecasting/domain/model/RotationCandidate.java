package com.emsafe.aiforecasting.domain.model;

import java.math.BigDecimal;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;

/**
 * Candidato viable de intercambio de puestos.
 *
 * @param accumulationRate tasa del candidato, que representa la exposición de su puesto
 * @param benefitIndex     {@code (source.rate − candidate.rate) × remaining}
 */
public record RotationCandidate(WorkerExposureSnapshot snapshot, BigDecimal accumulationRate,
                                BigDecimal benefitIndex) {

    public RotationCandidate {
        requirePresent(snapshot, "snapshot");
        requirePresent(accumulationRate, "accumulationRate");
        requirePresent(benefitIndex, "benefitIndex");
    }
}
