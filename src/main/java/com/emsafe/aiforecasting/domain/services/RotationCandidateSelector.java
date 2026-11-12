package com.emsafe.aiforecasting.domain.services;

import com.emsafe.aiforecasting.domain.model.Decimals;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.ForecastingThresholds;
import com.emsafe.aiforecasting.domain.model.RotationCandidate;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;

/**
 * Elige el mejor candidato para intercambiar puesto con el operario fuente.
 *
 * <p>Candidatos: misma organización y zona, otro operario y otro puesto. El fuente pasa al
 * puesto del candidato y viceversa; la tasa de cada uno representa la exposición de su puesto.
 * Viable si {@code candidate.current + source.rate × remaining ≤ candidate.limit × safeRatio}
 * y {@code candidate.rate < source.rate}. Gana el mayor beneficio
 * {@code (source.rate − candidate.rate) × remaining}; empate → menor índice, luego menor workerId.</p>
 */
public class RotationCandidateSelector {

    private static final Comparator<RotationCandidate> BEST_FIRST =
            Comparator.comparing(RotationCandidate::benefitIndex).reversed()
                    .thenComparing(c -> c.snapshot().accumulatedIndex())
                    .thenComparing(c -> c.snapshot().workerId());

    private final ExposureProjectionService projections;
    private final ForecastingThresholds thresholds;

    public RotationCandidateSelector(ExposureProjectionService projections, ForecastingThresholds thresholds) {
        this.projections = requirePresent(projections, "projections");
        this.thresholds = requirePresent(thresholds, "thresholds");
    }

    public Optional<RotationCandidate> selectBest(WorkerExposureSnapshot source, ExposureProjection sourceProjection,
                                                  Collection<WorkerExposureSnapshot> pool) {
        requirePresent(source, "source");
        requirePresent(sourceProjection, "sourceProjection");
        if (pool == null || source.zoneId() == null || source.positionId() == null) {
            return Optional.empty();
        }
        BigDecimal sourceRate = sourceProjection.accumulationRate();
        BigDecimal remaining = ExposureProjectionService.remainingSeconds(
                source.capturedAt(), sourceProjection.shiftEnd());

        return pool.stream()
                .filter(c -> isEligible(source, c))
                .map(c -> evaluate(c, sourceRate, remaining))
                .flatMap(Optional::stream)
                .min(BEST_FIRST);
    }

    private static boolean isEligible(WorkerExposureSnapshot source, WorkerExposureSnapshot candidate) {
        return candidate != null
                && Objects.equals(candidate.organizationId(), source.organizationId())
                && Objects.equals(candidate.zoneId(), source.zoneId())
                && !Objects.equals(candidate.workerId(), source.workerId())
                && candidate.positionId() != null
                && !Objects.equals(candidate.positionId(), source.positionId());
    }

    private Optional<RotationCandidate> evaluate(WorkerExposureSnapshot candidate, BigDecimal sourceRate,
                                                 BigDecimal remaining) {
        BigDecimal candidateRate;
        try {
            candidateRate = projections.accumulationRate(candidate.accumulatedIndex(),
                    candidate.sessionStartedAt(), candidate.capturedAt());
        } catch (ForecastingRuleException e) {
            // Un snapshot ajeno con datos inválidos no debe impedir evaluar al operario fuente.
            return Optional.empty();
        }
        if (candidateRate.compareTo(sourceRate) >= 0) {
            return Optional.empty();
        }
        BigDecimal candidateProjected = candidate.accumulatedIndex().add(sourceRate.multiply(remaining));
        BigDecimal safeCeiling = candidate.limitIndex().multiply(thresholds.safeCandidateRatio());
        if (candidateProjected.compareTo(safeCeiling) > 0) {
            return Optional.empty();
        }
        BigDecimal benefit = Decimals.scaled(sourceRate.subtract(candidateRate).multiply(remaining));
        return Optional.of(new RotationCandidate(candidate, candidateRate, benefit));
    }
}
