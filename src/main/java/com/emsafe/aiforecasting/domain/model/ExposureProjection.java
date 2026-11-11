package com.emsafe.aiforecasting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;

/**
 * Resultado de proyectar el índice acumulado (uT·s) hasta el fin del turno.
 * Es una proyección: no afirma que el límite se haya excedido.
 *
 * @param estimatedExceedanceAt momento estimado en que se supera el límite, o {@code null}
 */
public record ExposureProjection(BigDecimal currentIndex,
                                 BigDecimal limitIndex,
                                 BigDecimal accumulationRate,
                                 BigDecimal projectedIndexAtShiftEnd,
                                 BigDecimal remainingMargin,
                                 BigDecimal consumedRatio,
                                 BigDecimal projectedRatio,
                                 Instant estimatedExceedanceAt,
                                 Instant shiftEnd) {

    public ExposureProjection {
        requirePresent(currentIndex, "currentIndex");
        requirePresent(limitIndex, "limitIndex");
        requirePresent(accumulationRate, "accumulationRate");
        requirePresent(projectedIndexAtShiftEnd, "projectedIndexAtShiftEnd");
        requirePresent(remainingMargin, "remainingMargin");
        requirePresent(consumedRatio, "consumedRatio");
        requirePresent(projectedRatio, "projectedRatio");
        requirePresent(shiftEnd, "shiftEnd");
        if (limitIndex.signum() <= 0) {
            throw ForecastingRuleException.invalid("limitIndex must be greater than zero");
        }
    }

    public boolean projectsExceedance() {
        return estimatedExceedanceAt != null;
    }
}
