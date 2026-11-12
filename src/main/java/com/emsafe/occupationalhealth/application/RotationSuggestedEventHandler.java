package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.MitigationNoticeRequested;
import com.emsafe.occupationalhealth.application.events.inbound.RotationSuggested;
import com.emsafe.occupationalhealth.domain.model.*;
import com.emsafe.occupationalhealth.domain.services.ExposureQueryPort;
import com.emsafe.occupationalhealth.domain.services.FeasibilityResult;
import com.emsafe.occupationalhealth.domain.services.RotationFeasibilityService;
import com.emsafe.occupationalhealth.domain.services.ZoneExposurePort;
import com.emsafe.occupationalhealth.infrastructure.InboxDeduplicator;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Valida una rotación sugerida por el agente y la deja pendiente de decisión
 * (informe §5.6.3, RF29 y US61).
 *
 * El agente propone un operario en riesgo y una lista de candidatos; aquí se
 * busca el primero con el que el intercambio sea viable. Los puestos de destino
 * salen de los turnos: una rotación es un intercambio, así que cada uno va al
 * puesto que ocupa el otro.
 *
 * Si ningún candidato sirve, la sugerencia se cierra como NOT_VIABLE y se
 * propone reprogramar la tarea — el escenario 2 de US61.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RotationSuggestedEventHandler {

    private static final String CONSUMER = "RotationSuggestedConsumer";

    private final MitigationActionRepository actionRepository;
    private final WorkerShiftRepository shiftRepository;
    private final ExposureQueryPort exposureQueryPort;
    private final ZoneExposurePort zoneExposurePort;
    private final RotationFeasibilityService feasibilityService;
    private final ApplicationEventPublisher events;
    private final InboxDeduplicator inbox;

    @EventListener
    @Transactional
    public void on(RotationSuggested event) {
        if (!inbox.markAsProcessed(CONSUMER, event.envelope())) return;

        Long organizationId = event.envelope().organizationId();
        if (actionRepository.existsBySourceEvent(organizationId, event.envelope().eventId())) {
            log.debug("An action already exists for suggestion {}", event.suggestionId());
            return;
        }

        Optional<WorkerShift> atRiskShift = shiftRepository
                .findActiveByWorker(organizationId, event.workerId());
        if (atRiskShift.isEmpty()) {
            log.info("Suggestion {} ignored: worker {} has no active shift",
                    event.suggestionId(), event.workerId());
            return;
        }

        MitigationAction action = MitigationAction.propose(organizationId,
                MitigationType.ROTATION, MitigationOrigin.AI_SUGGESTION,
                MitigationTrigger.fromEvent(event.envelope().eventId(),
                        TriggerKind.ROTATION_SUGGESTED, event.projectedExceedanceAt()),
                event.rationale());

        Optional<WorkerShift> partner = firstViableCandidate(
                organizationId, atRiskShift.get(), event.candidateWorkerIds(), action);

        if (partner.isEmpty()) {
            closeWithRescheduling(organizationId, action, event);
            return;
        }

        MitigationAction saved = actionRepository.save(action);
        supersedeOtherOpenActions(organizationId, event.workerId(), saved);
        log.info("Rotation {} proposed between workers {} and {}", saved.getId(),
                event.workerId(), partner.get().getWorkerId());
    }

    /**
     * Prueba los candidatos en el orden en que los manda el agente y se queda
     * con el primero viable, componiendo el intercambio en la acción.
     *
     * Los operarios se añaden y se retiran según salga la evaluación: la
     * alternativa era evaluar fuera del agregado y duplicar aquí las reglas de
     * zona y margen, que es justo lo que RotationFeasibilityService evita.
     */
    private Optional<WorkerShift> firstViableCandidate(Long organizationId, WorkerShift atRisk,
                                                       List<Long> candidates,
                                                       MitigationAction action) {
        if (candidates == null) return Optional.empty();

        PositionAssignment atRiskNow = atRisk.currentAssignment().orElse(null);
        if (atRiskNow == null) return Optional.empty();

        for (Long candidateId : candidates) {
            Optional<WorkerShift> candidateShift = shiftRepository
                    .findActiveByWorker(organizationId, candidateId);
            if (candidateShift.isEmpty()) continue;

            PositionAssignment candidateNow = candidateShift.get().currentAssignment().orElse(null);
            if (candidateNow == null) continue;

            action.getAffectedWorkers().clear();
            // El operario en riesgo pasa al puesto del candidato y viceversa.
            action.addAffectedWorker(atRisk.getWorkerId(), atRiskNow.getJobPositionId(),
                    candidateNow.getJobPositionId(), candidateNow.getPlantZoneId(),
                    exposureQueryPort.currentExposure(organizationId, atRisk.getWorkerId()));
            action.addAffectedWorker(candidateId, candidateNow.getJobPositionId(),
                    atRiskNow.getJobPositionId(), atRiskNow.getPlantZoneId(),
                    exposureQueryPort.currentExposure(organizationId, candidateId));

            FeasibilityResult result = feasibilityService.evaluate(action,
                    workerId -> exposureQueryPort.currentExposure(organizationId, workerId),
                    zoneId -> zoneExposurePort.riskLevel(organizationId, zoneId));

            if (result.viable()) {
                return candidateShift;
            }
            log.debug("Candidate {} rejected: {}", candidateId, result.reason());
        }
        action.getAffectedWorkers().clear();
        return Optional.empty();
    }

    /** US61 escenario 2: no hay rotación viable, se propone reprogramar. */
    private void closeWithRescheduling(Long organizationId, MitigationAction action,
                                       RotationSuggested event) {
        action.addAffectedWorkerPendingTarget(event.workerId(), null,
                exposureQueryPort.currentExposure(organizationId, event.workerId()));
        action.declareNotViable("No rotation candidate with enough margin");
        MitigationAction closed = actionRepository.save(action);

        MitigationAction rescheduling = actionRepository.save(
                MitigationAction.proposeReschedulingFrom(closed));

        events.publishEvent(new MitigationNoticeRequested(
                EventEnvelope.of(organizationId), rescheduling.getId(), "SSO",
                rescheduling.affectedWorkerIds(),
                MitigationNoticeRequested.NOTICE_RESCHEDULING_PROPOSED, "warning"));

        log.info("Suggestion {} is not viable; rescheduling {} proposed",
                event.suggestionId(), rescheduling.getId());
    }

    private void supersedeOtherOpenActions(Long organizationId, Long workerId,
                                           MitigationAction replacement) {
        actionRepository.findOpenByWorker(organizationId, workerId).stream()
                .filter(a -> !a.getId().equals(replacement.getId()))
                .filter(MitigationAction::competesForWorker)
                .forEach(a -> {
                    a.supersedeBy(replacement);
                    actionRepository.save(a);
                });
    }
}
