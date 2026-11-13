package com.emsafe.aiforecasting.domain.model;

import java.math.BigDecimal;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;

/**
 * Umbrales configurables de criticidad y de viabilidad de candidatos.
 * Todos son ratios en el intervalo (0, 1].
 *
 * @param criticalMarginRatio    margen restante por debajo del cual se es CRITICAL/HIGH (0.10)
 * @param moderateProjectedRatio ratio proyectado a partir del cual se es MODERATE (0.90)
 * @param safeCandidateRatio     fracción del límite que un candidato no debe superar (0.90)
 */
public record ForecastingThresholds(BigDecimal criticalMarginRatio,
                                    BigDecimal moderateProjectedRatio,
                                    BigDecimal safeCandidateRatio) {

    public ForecastingThresholds {
        requireRatio(criticalMarginRatio, "criticalMarginRatio");
        requireRatio(moderateProjectedRatio, "moderateProjectedRatio");
        requireRatio(safeCandidateRatio, "safeCandidateRatio");
    }

    private static void requireRatio(BigDecimal value, String field) {
        requirePresent(value, field);
        if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw ForecastingRuleException.invalid(field + " must be in (0, 1]");
        }
    }
}
