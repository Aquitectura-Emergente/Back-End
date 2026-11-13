package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.inbound.ExposureSessionClosed;
import com.emsafe.occupationalhealth.domain.model.ClosedSessionRecord;
import com.emsafe.occupationalhealth.domain.model.DoseSnapshot;
import com.emsafe.occupationalhealth.domain.model.WorkerShift;
import com.emsafe.occupationalhealth.infrastructure.InboxDeduplicator;
import com.emsafe.occupationalhealth.infrastructure.persistence.ClosedSessionRecordRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Guarda la jornada consolidada y cierra el turno asociado
 * (informe §5.6.3, ExposureSessionClosedEventHandler).
 *
 * Es el único camino por el que un turno se cierra solo. El cierre manual
 * existe aparte, en StartWorkerShiftCommandHandler.close().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExposureSessionClosedEventHandler {

    private static final String CONSUMER = "ExposureSessionClosedConsumer";

    private final ClosedSessionRecordRepository closedSessions;
    private final WorkerShiftRepository shiftRepository;
    private final InboxDeduplicator inbox;

    @EventListener
    @Transactional
    public void on(ExposureSessionClosed event) {
        if (!inbox.markAsProcessed(CONSUMER, event.envelope())) return;

        Long organizationId = event.envelope().organizationId();

        // Reprocesar la misma jornada actualiza la proyección en vez de
        // duplicarla: es una copia local, no un registro histórico.
        ClosedSessionRecord record = closedSessions
                .findByOrganizationIdAndSessionId(organizationId, event.sessionId())
                .orElseGet(() -> ClosedSessionRecord.builder()
                        .organizationId(organizationId)
                        .sessionId(event.sessionId())
                        .workerId(event.workerId())
                        .build());

        record.setFinalDose(DoseSnapshot.of(event.finalDose(), event.unit(),
                event.methodId(), event.limit()));
        record.setCoverage(event.coverage());
        record.setFinalizedAt(event.finalizedAt());
        closedSessions.save(record);

        closeShiftOf(organizationId, event);
        log.info("Stored closed session {} for worker {}", event.sessionId(), event.workerId());
    }

    /**
     * Cierra el turno vinculado a esa jornada. Se busca por el turno activo del
     * operario y se comprueba que la jornada coincida: cerrar el turno de otra
     * jornada dejaría tramos abiertos sin dueño.
     */
    private void closeShiftOf(Long organizationId, ExposureSessionClosed event) {
        shiftRepository.findActiveByWorker(organizationId, event.workerId())
                .filter(shift -> matchesSession(shift, event.sessionId()))
                .ifPresent(shift -> {
                    shift.close(coherentCloseInstant(shift, event.finalizedAt()));
                    shiftRepository.save(shift);
                });
    }

    /**
     * El instante con el que cerrar el turno.
     *
     * Se usa el finalizedAt del evento, que es la hora real del suceso. Pero una
     * jornada sincronizada de forma diferida puede traer un instante ANTERIOR al
     * inicio del tramo vigente, y un tramo no puede acabar antes de empezar: el
     * agregado lo rechazaría y el consumidor caería con un 400 por un dato que
     * no es culpa de nadie.
     *
     * En ese caso se cierra en el momento actual. La hora real no se pierde: la
     * conserva el ClosedSessionRecord, que es lo que alimenta los reportes.
     */
    private LocalDateTime coherentCloseInstant(WorkerShift shift, LocalDateTime finalizedAt) {
        LocalDateTime currentStart = shift.currentAssignment()
                .map(a -> a.getInterval().getFrom())
                .orElse(null);
        if (finalizedAt != null && (currentStart == null || finalizedAt.isAfter(currentStart))) {
            return finalizedAt;
        }
        log.debug("Session finalizedAt {} precedes the current assignment start {}; "
                + "closing the shift now", finalizedAt, currentStart);
        return LocalDateTime.now();
    }

    /**
     * Un turno sin jornada vinculada se acepta: puede haberse abierto antes de
     * que Dosimetry le asignara una, y cerrarlo es lo correcto. Lo que no se
     * acepta es cerrar un turno vinculado a OTRA jornada.
     */
    private boolean matchesSession(WorkerShift shift, String sessionId) {
        return shift.getExposureSessionId() == null
                || shift.getExposureSessionId().equals(sessionId);
    }
}
