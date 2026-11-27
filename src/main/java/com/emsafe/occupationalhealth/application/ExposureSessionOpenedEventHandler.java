package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.ShiftUpdated;
import com.emsafe.occupationalhealth.application.events.inbound.ExposureSessionOpened;
import com.emsafe.occupationalhealth.domain.model.WorkerShift;
import com.emsafe.occupationalhealth.domain.services.JobPositionZonePort;
import com.emsafe.occupationalhealth.infrastructure.InboxDeduplicator;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Abre el turno del operario cuando Personal Dosimetry abre su jornada, en el
 * puesto de la jornada y en la zona de ese puesto, y lo vincula a la jornada.
 * Es el espejo de {@link ExposureSessionClosedEventHandler}, que lo cierra.
 *
 * <p>Así el SSO inicia la jornada una sola vez y el operario aparece en el mapa
 * de planta. Después se publica {@link ShiftUpdated} con la zona, para que AI
 * Forecasting pueda proponer rotaciones y no solo reprogramar la tarea.</p>
 *
 * <p>Si el operario ya tenía un turno abierto (lo abrió el SSO a mano), no se
 * abre otro: solo se le vincula la jornada si no tenía ninguna.</p>
 *
 * <p>Corre después del commit de la entrega, en una transacción nueva que se
 * confirma dentro del propio manejador. Así un fallo aquí, incluido uno al
 * confirmar, no sale hacia el relay ni deshace la entrega a los demás contextos
 * ni la jornada, que ya está abierta; se registra en el log.</p>
 */
@Slf4j
@Service
public class ExposureSessionOpenedEventHandler {

    private static final String CONSUMER = "ExposureSessionOpenedConsumer";

    private final WorkerShiftRepository shifts;
    private final StartWorkerShiftCommandHandler starter;
    private final JobPositionZonePort zones;
    private final InboxDeduplicator inbox;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public ExposureSessionOpenedEventHandler(WorkerShiftRepository shifts, StartWorkerShiftCommandHandler starter,
                                             JobPositionZonePort zones, InboxDeduplicator inbox,
                                             ApplicationEventPublisher events, PlatformTransactionManager transactions) {
        this.shifts = shifts;
        this.starter = starter;
        this.zones = zones;
        this.inbox = inbox;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(ExposureSessionOpened event) {
        if (event.workerId() == null || event.positionId() == null) return;
        try {
            tx.executeWithoutResult(status -> open(event));
        } catch (RuntimeException e) {
            log.warn("Could not open the shift of worker {} for session {}: {}",
                    event.workerId(), event.sessionId(), e.toString());
        }
    }

    private void open(ExposureSessionOpened event) {
        Long organizationId = event.envelope().organizationId();
        if (!inbox.markAsProcessed(CONSUMER, event.envelope())) return;
        Optional<WorkerShift> active = shifts.findActiveByWorker(organizationId, event.workerId());
        if (active.isPresent()) {
            linkIfFree(active.get(), event.sessionId());
            return;
        }
        Optional<Long> zone = zones.zoneOf(organizationId, event.positionId());
        if (zone.isEmpty()) {
            log.warn("No shift opened for session {}: job position {} has no plant zone",
                    event.sessionId(), event.positionId());
            return;
        }
        starter.handle(organizationId, event.workerId(), event.positionId(), zone.get(), event.sessionId());
        events.publishEvent(new ShiftUpdated(EventEnvelope.causedBy(organizationId, event.envelope()),
                event.sessionId(), null, event.workerId(), null, event.positionId(), zone.get(),
                LocalDateTime.now(), null));
        log.info("Shift opened for worker {} in zone {} with session {}",
                event.workerId(), zone.get(), event.sessionId());
    }

    private void linkIfFree(WorkerShift shift, String sessionId) {
        if (shift.getExposureSessionId() == null) {
            shift.linkExposureSession(sessionId);
            shifts.save(shift);
            log.info("Session {} linked to the open shift #{} of worker {}", sessionId, shift.getId(), shift.getWorkerId());
        } else if (!shift.getExposureSessionId().equals(sessionId)) {
            log.warn("Worker {} already has shift #{} linked to session {}; session {} not linked",
                    shift.getWorkerId(), shift.getId(), shift.getExposureSessionId(), sessionId);
        }
    }
}
