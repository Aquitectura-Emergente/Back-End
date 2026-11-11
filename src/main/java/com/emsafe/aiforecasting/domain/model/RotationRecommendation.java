package com.emsafe.aiforecasting.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;
import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/**
 * Recomendación preventiva. No se ejecuta sola: la decide el SSO en Occupational Health.
 *
 * @param estimatedBenefitIndex índice (uT·s) que se estima evitar al operario fuente
 */
public record RotationRecommendation(RecommendationType type,
                                     Long sourceWorkerId,
                                     Long targetWorkerId,
                                     Long sourcePositionId,
                                     Long targetPositionId,
                                     BigDecimal estimatedBenefitIndex,
                                     String reason) {

    public RotationRecommendation {
        requirePresent(type, "type");
        requirePresent(sourceWorkerId, "sourceWorkerId");
        requirePresent(estimatedBenefitIndex, "estimatedBenefitIndex");
        requireText(reason, "reason");
        if (estimatedBenefitIndex.signum() < 0) {
            throw ForecastingRuleException.invalid("estimatedBenefitIndex must not be negative");
        }
        if (type == RecommendationType.ROTATION) {
            requirePresent(targetWorkerId, "targetWorkerId");
            requirePresent(sourcePositionId, "sourcePositionId");
            requirePresent(targetPositionId, "targetPositionId");
            if (Objects.equals(sourceWorkerId, targetWorkerId)) {
                throw ForecastingRuleException.invalid("a worker cannot rotate with itself");
            }
        } else if (targetWorkerId != null || targetPositionId != null) {
            throw ForecastingRuleException.invalid("RESCHEDULE_TASK has no target worker or position");
        }
    }
}
