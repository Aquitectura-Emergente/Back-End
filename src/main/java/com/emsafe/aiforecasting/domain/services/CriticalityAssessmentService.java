package com.emsafe.aiforecasting.domain.services;

import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ForecastingThresholds;

import java.math.BigDecimal;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;

/**
 * Prioriza una proyección. Las reglas se evalúan en orden y gana la primera:
 * <ol>
 *   <li>CRITICAL: margen &lt; criticalMarginRatio × límite y sin candidato viable.</li>
 *   <li>HIGH: projectedRatio &gt; 1, o margen bajo con candidato viable.</li>
 *   <li>MODERATE: projectedRatio ≥ moderateProjectedRatio.</li>
 *   <li>LOW: cualquier otro caso.</li>
 * </ol>
 */
public class CriticalityAssessmentService {

    private final ForecastingThresholds thresholds;

    public CriticalityAssessmentService(ForecastingThresholds thresholds) {
        this.thresholds = requirePresent(thresholds, "thresholds");
    }

    public CriticalityLevel assess(ExposureProjection projection, boolean hasViableCandidate) {
        requirePresent(projection, "projection");
        BigDecimal criticalMargin = projection.limitIndex().multiply(thresholds.criticalMarginRatio());
        boolean lowMargin = projection.remainingMargin().compareTo(criticalMargin) < 0;

        if (lowMargin && !hasViableCandidate) {
            return CriticalityLevel.CRITICAL;
        }
        if (projection.projectedRatio().compareTo(BigDecimal.ONE) > 0 || lowMargin) {
            return CriticalityLevel.HIGH;
        }
        if (projection.projectedRatio().compareTo(thresholds.moderateProjectedRatio()) >= 0) {
            return CriticalityLevel.MODERATE;
        }
        return CriticalityLevel.LOW;
    }
}
