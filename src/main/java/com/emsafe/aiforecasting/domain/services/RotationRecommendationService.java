package com.emsafe.aiforecasting.domain.services;

import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.Decimals;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RotationCandidate;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;

import java.math.BigDecimal;
import java.util.Optional;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;

/**
 * Traduce criticidad + candidato a una recomendación. Solo HIGH y CRITICAL reciben una:
 * ROTATION si hay candidato viable; si no, RESCHEDULE_TASK sin destino. El motivo
 * contiene solo IDs, nunca datos personales.
 */
public class RotationRecommendationService {

    public Optional<RotationRecommendation> recommend(CriticalityLevel criticality, WorkerExposureSnapshot source,
                                                      ExposureProjection projection,
                                                      Optional<RotationCandidate> candidate) {
        requirePresent(criticality, "criticality");
        requirePresent(source, "source");
        requirePresent(projection, "projection");
        if (!criticality.requiresRecommendation()) {
            return Optional.empty();
        }
        if (candidate != null && candidate.isPresent()) {
            return Optional.of(rotation(source, candidate.get()));
        }
        return Optional.of(reschedule(source, projection));
    }

    private static RotationRecommendation rotation(WorkerExposureSnapshot source, RotationCandidate candidate) {
        WorkerExposureSnapshot target = candidate.snapshot();
        String reason = "Intercambiar al operario " + source.workerId() + " (puesto " + source.positionId()
                + ") con el operario " + target.workerId() + " (puesto " + target.positionId()
                + "): el puesto " + target.positionId() + " acumula a menor tasa.";
        return new RotationRecommendation(RecommendationType.ROTATION, source.workerId(), target.workerId(),
                source.positionId(), target.positionId(), candidate.benefitIndex(), reason);
    }

    private static RotationRecommendation reschedule(WorkerExposureSnapshot source, ExposureProjection projection) {
        // Reprogramar evita lo que falta acumular hasta el fin del turno.
        BigDecimal avoided = Decimals.scaled(
                projection.projectedIndexAtShiftEnd().subtract(projection.currentIndex()).max(BigDecimal.ZERO));
        String reason = "Sin candidato viable en la zona: reprogramar la tarea del operario "
                + source.workerId() + " para el resto del turno.";
        return new RotationRecommendation(RecommendationType.RESCHEDULE_TASK, source.workerId(), null,
                source.positionId(), null, avoided, reason);
    }
}
