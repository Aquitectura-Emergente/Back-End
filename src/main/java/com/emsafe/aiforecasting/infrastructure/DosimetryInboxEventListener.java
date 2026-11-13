package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.InboxOutcome;
import com.emsafe.aiforecasting.application.TriggerAssessmentHandler;
import com.emsafe.aiforecasting.application.UpsertExposureSnapshotHandler;
import com.emsafe.aiforecasting.application.commands.ExposureWarningRaisedCommand;
import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de entrada para lo que publica el relay de Personal Dosimetry.
 *
 * <p>Cierra el último cable que quedaba suelto: los dos inbox que este contexto
 * expone para dosimetría —{@code exposure-snapshot} y
 * {@code exposure-warning-raised}— solo eran alcanzables por HTTP y ningún
 * productor los invocaba, así que la evaluación preventiva no se disparaba nunca
 * sola. El método y la unidad que valida {@link
 * com.emsafe.aiforecasting.domain.model.ExposureMethod} ya coincidían con lo que
 * dosimetría emite ({@code MAGNETIC_TIME_INDEX_V1} y {@code uT*s}): no había que
 * negociar nada, solo faltaba quien llamara.</p>
 *
 * <h2>Por qué este listener no es como {@link ShiftUpdatedEventListener}</h2>
 *
 * <p>Los dos puentes se parecen, pero las dos decisiones de aquel se invierten
 * aquí, y por la misma razón: al otro lado hay una tabla de outbox con
 * reintento.</p>
 *
 * <p><b>Síncrono y no AFTER_COMMIT.</b> El relay publica dentro de su propia
 * transacción y decide si marca el evento como publicado según si la entrega
 * funcionó. Con {@code AFTER_COMMIT} lo marcaría antes de saberlo, y un fallo
 * aquí se traduciría en un hecho dado por entregado que nadie procesó.</p>
 *
 * <p><b>El fallo se propaga.</b> La regla general es que un consumidor no
 * propague: el hecho ya es irreversible y no se le puede devolver un error a
 * quien hizo la acción. Esa regla vale cuando al otro lado hay una persona, como
 * en {@code ShiftUpdated}. Aquí el que llama es un relay programado cuyo oficio
 * es exactamente reintentar: tragarse la excepción le haría marcar el evento como
 * entregado y perderlo. Propagar <em>es</em> lo que activa el reintento.</p>
 *
 * <p><b>REQUIRES_NEW.</b> Para que ese fallo no se lleve por delante la pasada
 * entera. {@code drainOnce()} es transaccional y marca ahí los eventos que sí
 * entregó; si esta transacción se uniera a la suya, un 409 la dejaría
 * rollback-only y la pasada perdería también las entregas buenas. En una
 * transacción nueva, el 409 solo deshace lo de este evento.</p>
 */
@Component
public class DosimetryInboxEventListener {

    private static final Logger log = LoggerFactory.getLogger(DosimetryInboxEventListener.class);

    private final UpsertExposureSnapshotHandler snapshots;
    private final TriggerAssessmentHandler assessments;

    public DosimetryInboxEventListener(UpsertExposureSnapshotHandler snapshots,
                                       TriggerAssessmentHandler assessments) {
        this.snapshots = snapshots;
        this.assessments = assessments;
    }

    /** Apertura de jornada: alta del modelo de lectura de la sesión. */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(UpsertExposureSnapshotCommand command) {
        InboxOutcome outcome = snapshots.handle(command);
        if (outcome.duplicate()) {
            log.debug("exposure-snapshot {} already processed", outcome.eventId());
        } else {
            log.info("AI Forecasting tracks session {} (worker {})", command.sessionId(), command.workerId());
        }
    }

    /**
     * Aviso de exposición: evaluación preventiva completa.
     *
     * <p>Un 409 aquí es casi siempre que la sesión todavía no tiene snapshot
     * porque la apertura va detrás en la cola. No es un error: el relay lo deja
     * pendiente y la pasada siguiente ya lo encuentra creado.</p>
     */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ExposureWarningRaisedCommand command) {
        InboxOutcome outcome = assessments.handle(command);
        if (outcome.duplicate()) {
            log.debug("exposure-warning-raised {} already processed", outcome.eventId());
        } else {
            log.info("AI Forecasting assessed session {} on warning (assessment {})",
                    command.sessionId(), outcome.assessmentId());
        }
    }
}
