package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.inbound.OccupationalLimitExceeded;
import com.emsafe.occupationalhealth.domain.model.*;
import com.emsafe.occupationalhealth.infrastructure.InboxDeduplicator;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Abre una medida pendiente cuando se confirma una excedencia
 * (informe §5.6.3, RF19).
 *
 * No solicita avisos propios: Alerts ya notificó la excedencia, y pedir otro
 * aviso duplicaría la notificación al operario.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OccupationalLimitExceededEventHandler {

    private static final String CONSUMER = "OccupationalLimitExceededConsumer";

    private final MitigationActionRepository actionRepository;
    private final WorkerShiftRepository shiftRepository;
    private final InboxDeduplicator inbox;

    @EventListener
    @Transactional
    public void on(OccupationalLimitExceeded event) {
        if (!inbox.markAsProcessed(CONSUMER, event.envelope())) return;

        Long organizationId = event.envelope().organizationId();

        // El UNIQUE (organization_id, source_event_id) ya lo impediría, pero
        // comprobarlo antes evita una excepción de integridad en el camino normal.
        if (actionRepository.existsBySourceEvent(organizationId, event.envelope().eventId())) {
            log.debug("An action already exists for event {}", event.envelope().eventId());
            return;
        }

        // El tercer parámetro es el methodId, no el limitVersionId: hasta el
        // 2026-11-12 se pasaba la versión del límite y `dose_method_id` guardaba
        // un dato que no era el suyo.
        DoseSnapshot dose = DoseSnapshot.of(event.dose(), event.unit(),
                event.methodId(), event.limit());

        MitigationAction action = MitigationAction.propose(organizationId,
                MitigationType.RELOCATION, MitigationOrigin.LIMIT_EXCEEDED,
                MitigationTrigger.fromEvent(event.envelope().eventId(),
                        TriggerKind.OCCUPATIONAL_LIMIT_EXCEEDED, event.detectedAt()),
                "Occupational limit exceeded on session " + event.sessionId());

        // El destino no se conoce todavía: la excedencia dice que hay que sacar
        // al operario de donde está, no a dónde llevarlo. Eso lo decide el SSO al
        // aprobar, así que la propuesta nace sin zona destino y el agregado lo
        // permite porque todavía no se está aplicando nada.
        Long currentPosition = shiftRepository
                .findActiveByWorker(organizationId, event.workerId())
                .flatMap(WorkerShift::currentAssignment)
                .map(PositionAssignment::getJobPositionId)
                .orElse(null);

        action.addAffectedWorkerPendingTarget(event.workerId(), currentPosition, dose);
        MitigationAction saved = actionRepository.save(action);

        supersedeOtherOpenActions(organizationId, event.workerId(), saved);
        log.info("Opened mitigation action {} for worker {} after limit exceeded",
                saved.getId(), event.workerId());
    }

    /** Regla 5: una sola medida viva por operario. */
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
