package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.MitigationActionApplied;
import com.emsafe.occupationalhealth.application.events.MitigationNoticeRequested;
import com.emsafe.occupationalhealth.application.events.ShiftUpdated;
import com.emsafe.occupationalhealth.domain.model.*;
import com.emsafe.occupationalhealth.domain.services.ExposureQueryPort;
import com.emsafe.occupationalhealth.domain.services.ZoneExposurePort;
import com.emsafe.occupationalhealth.infrastructure.MitigationAuditAdapter;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Reubicación manual decidida por el SSO (informe §5.6.3).
 *
 * A diferencia de aprobar una sugerencia, aquí la decisión y la aplicación son
 * el mismo acto: el SSO ya decidió al registrarla. Aun así la acción recorre
 * PROPOSED → APPROVED → APPLIED en lugar de nacer aplicada, para que la
 * bitácora cuente los tres pasos y el historial sea uniforme con el de las
 * medidas automáticas.
 */
@Service
@RequiredArgsConstructor
public class RegisterRelocationCommandHandler {

    private final MitigationActionRepository actionRepository;
    private final WorkerShiftRepository shiftRepository;
    private final ExposureQueryPort exposureQueryPort;
    private final ZoneExposurePort zoneExposurePort;
    private final MitigationAuditAdapter audit;
    private final ApplicationEventPublisher events;

    @Transactional
    public MitigationAction handle(Long organizationId, Long ssoUserId, Long workerId,
                                   Long toPositionId, Long toZoneId, String justification) {
        WorkerShift shift = shiftRepository.findActiveByWorker(organizationId, workerId)
                .orElseThrow(() -> new MitigationNotViableException(
                        "Worker " + workerId + " has no active shift to relocate"));

        DoseSnapshot current = exposureQueryPort.currentExposure(organizationId, workerId);

        // La zona destino manda: se valida antes de crear nada, para no dejar una
        // acción registrada que nunca se pudo aplicar.
        ZoneRiskLevel target = zoneExposurePort.riskLevel(organizationId, toZoneId);
        if (!target.admits(current)) {
            throw new MitigationNotViableException(target.rejectionReason());
        }

        Long previousPositionId = shift.currentAssignment()
                .map(PositionAssignment::getJobPositionId)
                .orElse(null);

        MitigationAction action = MitigationAction.propose(organizationId,
                MitigationType.RELOCATION, MitigationOrigin.MANUAL,
                MitigationTrigger.manual(), justification);
        action.addAffectedWorker(workerId, previousPositionId, toPositionId, toZoneId, current);
        action.approve(Decision.approvedBy(ssoUserId, justification));
        // Se guarda antes de reasignar para que la acción tenga id: el tramo
        // nuevo del turno guarda sourceActionId y sin id no habría trazabilidad.
        MitigationAction saved = actionRepository.save(action);

        supersedeOpenActionsFor(organizationId, workerId, saved);

        LocalDateTime effectiveAt = LocalDateTime.now();
        shift.reassign(toPositionId, toZoneId, effectiveAt, saved.getId());
        shiftRepository.save(shift);

        saved.markApplied("Manual relocation applied at " + effectiveAt);
        saved = actionRepository.save(saved);

        audit.recordGranted(organizationId, ssoUserId, "MitigationAction", saved.getId(),
                MitigationAuditEntry.ACTION_RELOCATE,
                "Worker " + workerId + " relocated to zone " + toZoneId);

        EventEnvelope envelope = EventEnvelope.of(organizationId);
        events.publishEvent(new ShiftUpdated(envelope, shift.getExposureSessionId(),
                saved.getId(), workerId, previousPositionId, toPositionId, toZoneId,
                effectiveAt, ssoUserId));
        events.publishEvent(new MitigationActionApplied(
                EventEnvelope.causedBy(organizationId, envelope), saved.getId(),
                saved.getType(), List.of(workerId), effectiveAt, ssoUserId));
        events.publishEvent(new MitigationNoticeRequested(
                EventEnvelope.causedBy(organizationId, envelope), saved.getId(),
                "WORKER", List.of(workerId),
                MitigationNoticeRequested.NOTICE_MITIGATION_APPLIED, "info"));
        return saved;
    }

    /**
     * Regla 5: un operario tiene como máximo una medida viva que lo mueva. Una
     * reubicación nueva desplaza la propuesta pendiente y deja constancia.
     */
    private void supersedeOpenActionsFor(Long organizationId, Long workerId,
                                         MitigationAction replacement) {
        List<MitigationAction> open = actionRepository.findOpenByWorker(organizationId, workerId);
        for (MitigationAction pending : open) {
            // La recién creada también sale en la consulta: hay que excluirla o
            // se desplazaría a sí misma.
            if (pending.getId().equals(replacement.getId())) continue;
            if (pending.competesForWorker()) {
                pending.supersedeBy(replacement);
                actionRepository.save(pending);
            }
        }
    }
}
