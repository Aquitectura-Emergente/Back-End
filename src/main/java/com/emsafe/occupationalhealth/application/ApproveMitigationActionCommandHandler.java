package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.MitigationActionApplied;
import com.emsafe.occupationalhealth.application.events.MitigationNoticeRequested;
import com.emsafe.occupationalhealth.application.events.ShiftUpdated;
import com.emsafe.occupationalhealth.domain.model.*;
import com.emsafe.occupationalhealth.domain.services.ExposureQueryPort;
import com.emsafe.occupationalhealth.domain.services.FeasibilityResult;
import com.emsafe.occupationalhealth.domain.services.RotationFeasibilityService;
import com.emsafe.occupationalhealth.domain.services.ZoneExposurePort;
import com.emsafe.occupationalhealth.infrastructure.MitigationAuditAdapter;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Aprueba una medida: revalida, decide, reasigna turnos y publica los hechos
 * (informe §5.6.3).
 *
 * El orden importa y es el del informe: primero revalidar contra la realidad de
 * ahora, porque la sugerencia del agente pudo quedar desactualizada (regla 4);
 * solo si sigue siendo viable se registra la decisión y se mueve a la gente.
 *
 * ── Desvío sobre el informe: no hay outbox ────────────────────────────────────
 * El informe confirma en una transacción la acción, los turnos y una tabla
 * outbox, y deja que un publicador independiente entregue los eventos a Azure
 * Service Bus. Esta solución no tiene broker, así que los eventos se publican
 * en proceso con ApplicationEventPublisher y los consumidores los reciben con
 * @TransactionalEventListener(AFTER_COMMIT). La garantía que importa se
 * mantiene: nadie ve un hecho que la base no haya confirmado. Lo que se pierde
 * frente al outbox es el reintento si el consumidor falla — cuando exista
 * broker, se sustituye el publicador y los eventos no cambian.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApproveMitigationActionCommandHandler {

    private final MitigationActionRepository actionRepository;
    private final WorkerShiftRepository shiftRepository;
    private final ExposureQueryPort exposureQueryPort;
    private final ZoneExposurePort zoneExposurePort;
    private final RotationFeasibilityService feasibilityService;
    private final MitigationAuditAdapter audit;
    private final ApplicationEventPublisher events;

    /**
     * @param ssoUserId quién decide; ya validado como SSO de la organización
     * @return la acción tal como quedó: APPLIED si se aplicó, NOT_VIABLE si la
     *         revalidación la invalidó
     */
    @Transactional
    public MitigationAction handle(Long organizationId, Long actionId, Long ssoUserId,
                                   String reason, Long toPositionId, Long toZoneId) {
        MitigationAction action = actionRepository
                .findByIdAndOrganization(actionId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("MitigationAction", actionId));

        if (action.getState() != MitigationState.PROPOSED) {
            throw new MitigationConflictException(
                    "Only a PROPOSED action can be approved, this one is " + action.getState());
        }

        // Una acción abierta por una excedencia no trae destino: el hecho dice de
        // dónde sacar al operario, no a dónde llevarlo. El SSO lo decide aquí.
        if (action.hasPendingTarget()) {
            if (toPositionId == null || toZoneId == null) {
                throw new MitigationUnprocessableException(
                        "This action has no target yet: approving it requires "
                                + "toPositionId and toZoneId");
            }
            action.assignTarget(toPositionId, toZoneId);
        }

        // Regla 4: la exposición de la propuesta pudo quedar vieja. Se vuelve a
        // consultar antes de decidir, y la foto nueva queda guardada en la acción.
        action.refreshDosesAndCheckMargin(
                workerId -> exposureQueryPort.currentExposure(organizationId, workerId));

        FeasibilityResult feasibility = feasibilityService.evaluate(
                action,
                workerId -> exposureQueryPort.currentExposure(organizationId, workerId),
                zoneId -> zoneExposurePort.riskLevel(organizationId, zoneId));

        if (feasibility.isNotViable()) {
            return closeAsNotViable(action, feasibility.reason());
        }

        LocalDateTime effectiveAt = LocalDateTime.now();
        action.approve(Decision.approvedBy(ssoUserId, reason));

        for (AffectedWorker worker : action.getAffectedWorkers()) {
            applyTo(worker, action, effectiveAt);
        }

        action.markApplied("Applied at " + effectiveAt);
        MitigationAction saved = actionRepository.save(action);

        audit.recordGranted(organizationId, ssoUserId, "MitigationAction", saved.getId(),
                MitigationAuditEntry.ACTION_APPROVE,
                "Applied " + saved.getType() + " for workers " + saved.affectedWorkerIds());

        EventEnvelope envelope = EventEnvelope.of(organizationId);
        events.publishEvent(new MitigationActionApplied(envelope, saved.getId(),
                saved.getType(), saved.affectedWorkerIds(), effectiveAt, ssoUserId));
        events.publishEvent(new MitigationNoticeRequested(
                EventEnvelope.causedBy(organizationId, envelope), saved.getId(),
                "WORKER", saved.affectedWorkerIds(),
                MitigationNoticeRequested.NOTICE_MITIGATION_APPLIED, "info"));
        return saved;
    }

    /**
     * Mueve al operario en su turno y avisa a Dosimetry.
     *
     * Si el operario no tiene turno abierto la medida falla en lugar de
     * aplicarse a medias: reasignar a alguien que no está trabajando dejaría la
     * acción marcada como aplicada sin que nada haya cambiado en la planta.
     */
    private void applyTo(AffectedWorker worker, MitigationAction action,
                         LocalDateTime effectiveAt) {
        WorkerShift shift = shiftRepository
                .findActiveByWorker(action.getOrganizationId(), worker.getWorkerId())
                .orElseThrow(() -> new MitigationNotViableException(
                        "Worker " + worker.getWorkerId() + " has no active shift to reassign"));

        Long previousPositionId = shift.currentAssignment()
                .map(PositionAssignment::getJobPositionId)
                .orElse(null);

        shift.reassign(worker.getToPositionId(), worker.getToZoneId(),
                effectiveAt, action.getId());
        shiftRepository.save(shift);

        events.publishEvent(new ShiftUpdated(
                EventEnvelope.of(action.getOrganizationId()),
                shift.getExposureSessionId(), action.getId(), worker.getWorkerId(),
                previousPositionId, worker.getToPositionId(), effectiveAt,
                action.getDecision() != null ? action.getDecision().getDecidedBy() : null));
    }

    /**
     * Regla 4: sin candidato con margen la acción se cierra y se propone
     * reprogramar la tarea. No se lanza excepción porque el resultado es
     * legítimo y hay que persistirlo — el controller lo traduce a 409.
     */
    private MitigationAction closeAsNotViable(MitigationAction action, String reason) {
        log.info("Action {} is no longer viable: {}", action.getId(), reason);
        action.declareNotViable(reason);
        MitigationAction closed = actionRepository.save(action);

        MitigationAction rescheduling = actionRepository.save(
                MitigationAction.proposeReschedulingFrom(closed));

        EventEnvelope envelope = EventEnvelope.of(action.getOrganizationId());
        events.publishEvent(new MitigationNoticeRequested(envelope, rescheduling.getId(),
                "SSO", rescheduling.affectedWorkerIds(),
                MitigationNoticeRequested.NOTICE_RESCHEDULING_PROPOSED, "warning"));
        return closed;
    }
}
