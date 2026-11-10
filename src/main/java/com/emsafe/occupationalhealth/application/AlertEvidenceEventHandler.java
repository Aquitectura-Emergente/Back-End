package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.inbound.AlertEvidenceReported;
import com.emsafe.occupationalhealth.domain.model.AlertEvidenceRecord;
import com.emsafe.occupationalhealth.domain.model.MitigationAction;
import com.emsafe.occupationalhealth.infrastructure.InboxDeduplicator;
import com.emsafe.occupationalhealth.infrastructure.persistence.AlertEvidenceRecordRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guarda la evidencia de acuse y escalamiento y, ante un escalamiento, eleva la
 * prioridad de la propuesta del operario (informe §5.6.3, RF25, US60 y regla 10).
 *
 * Este contexto NO decide ni escala alertas. Solo registra lo que publica Alerts
 * & Notification Dispatching, según la tabla de responsabilidad por tipo de
 * alerta de §4.1.4.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEvidenceEventHandler {

    private static final String CONSUMER = "AlertEvidenceConsumer";

    private final AlertEvidenceRecordRepository evidenceRepository;
    private final MitigationActionRepository actionRepository;
    private final InboxDeduplicator inbox;

    @EventListener
    @Transactional
    public void on(AlertEvidenceReported event) {
        if (!inbox.markAsProcessed(CONSUMER, event.envelope())) return;

        Long organizationId = event.envelope().organizationId();

        AlertEvidenceRecord record = evidenceRepository
                .findByOrganizationIdAndAlertId(organizationId, event.alertId())
                .orElseGet(() -> AlertEvidenceRecord.builder()
                        .organizationId(organizationId)
                        .alertId(event.alertId())
                        .workerId(event.workerId())
                        .sessionId(event.sessionId())
                        .alertType(event.alertType())
                        .build());

        // Las marcas se sellan con la hora REAL del suceso, que viene en el
        // evento, no con el reloj de ahora: una alerta sincronizada de forma
        // diferida debe constar cuándo ocurrió, no cuándo llegó.
        if (event.isEscalation()) {
            record.escalateAt(event.occurredAt());
        } else {
            record.acknowledgeAt(event.occurredAt());
        }
        evidenceRepository.save(record);

        if (record.isEscalatedWithoutAcknowledgement() && event.workerId() != null) {
            raisePriorityOfOpenActions(organizationId, event.workerId());
        }
    }

    /**
     * Regla 10: la propuesta sube a prioridad alta y se muestra primero en la
     * bandeja. raisePriority() ignora por su cuenta las acciones ya cerradas,
     * así que no hace falta filtrarlas aquí.
     */
    private void raisePriorityOfOpenActions(Long organizationId, Long workerId) {
        for (MitigationAction action : actionRepository.findOpenByWorker(organizationId, workerId)) {
            action.raisePriority();
            actionRepository.save(action);
            log.info("Action {} raised to HIGH after an escalation without acknowledgement",
                    action.getId());
        }
    }
}
