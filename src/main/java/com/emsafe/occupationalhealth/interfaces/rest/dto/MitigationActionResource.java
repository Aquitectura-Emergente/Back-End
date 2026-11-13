package com.emsafe.occupationalhealth.interfaces.rest.dto;

import com.emsafe.occupationalhealth.domain.model.MitigationAction;
import com.emsafe.occupationalhealth.domain.model.MitigationActionTransition;

import java.util.List;

/**
 * Una medida de mitigación tal como la ven el móvil y el portal
 * (informe §5.6.2, MitigationActionResource).
 *
 * Lleva la bitácora de transiciones incluida: es lo que hace auditable la
 * decisión sin una segunda llamada.
 */
public record MitigationActionResource(
        Long id,
        String type,
        String state,
        String origin,
        String priority,
        String justification,
        String outcome,
        Long supersededById,
        TriggerResource trigger,
        DecisionResource decision,
        List<AffectedWorkerResource> affectedWorkers,
        List<TransitionResource> history,
        String createdAt
) {
    public static MitigationActionResource from(MitigationAction a) {
        return new MitigationActionResource(
                a.getId(),
                a.getType().name(),
                a.getState().name(),
                a.getOrigin().name(),
                a.getPriority().name(),
                a.getJustification(),
                a.getOutcome(),
                a.getSupersededById(),
                TriggerResource.from(a.getTrigger()),
                DecisionResource.from(a.getDecision()),
                a.getAffectedWorkers().stream().map(AffectedWorkerResource::from).toList(),
                a.getHistory().stream().map(TransitionResource::from).toList(),
                a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
    }

    /** Una entrada de la bitácora append-only. */
    public record TransitionResource(String fromState, String toState, Long actorId, String at) {
        static TransitionResource from(MitigationActionTransition t) {
            return new TransitionResource(
                    t.getFromState() != null ? t.getFromState().name() : null,
                    t.getToState().name(),
                    t.getActorId(),
                    t.getOccurredAt() != null ? t.getOccurredAt().toString() : null);
        }
    }
}
