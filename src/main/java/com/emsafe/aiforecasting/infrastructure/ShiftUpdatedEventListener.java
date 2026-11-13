package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.InboxOutcome;
import com.emsafe.aiforecasting.application.ShiftUpdatedHandler;
import com.emsafe.aiforecasting.application.commands.ShiftUpdatedCommand;
import com.emsafe.occupationalhealth.application.events.ShiftUpdated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Adaptador de entrada: conecta el {@code ShiftUpdated} que publica Occupational
 * Health con el inbox de este contexto, que hasta ahora solo era alcanzable por
 * HTTP y por tanto nunca se invocaba desde dentro del backend.
 *
 * <p>Vive en AI Forecasting y no en Occupational Health a propósito: el que
 * publica no debe conocer a sus consumidores. Así, cuando exista broker, se
 * sustituye esta clase por un suscriptor sin tocar el contexto que emite.</p>
 *
 * <h2>Tres decisiones que no son evidentes</h2>
 *
 * <p><b>AFTER_COMMIT.</b> La rotación ya está confirmada en base de datos cuando
 * esto corre: nadie reacciona a un hecho que podría deshacerse.</p>
 *
 * <p><b>REQUIRES_NEW.</b> En AFTER_COMMIT la transacción que publicó el evento
 * está completándose y ya no acepta escrituras. Con la propagación por defecto,
 * el {@code @Transactional} del handler se uniría a ella y el snapshot
 * actualizado <em>no se guardaría</em>, sin error visible. Hace falta una
 * transacción nueva.</p>
 *
 * <p><b>No propagar el fallo.</b> El inbox responde 409 si la sesión no tiene
 * snapshot previo, y eso es normal: un operario puede rotar sin que AI
 * Forecasting siga su sesión. Propagarlo haría que el SSO viera un error después
 * de una reubicación que sí se aplicó y que no se puede deshacer. Se registra y
 * se sigue. Esto es lo que se pierde al no tener outbox: no hay reintento.</p>
 */
@Component
public class ShiftUpdatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ShiftUpdatedEventListener.class);

    private final ShiftUpdatedHandler handler;

    public ShiftUpdatedEventListener(ShiftUpdatedHandler handler) {
        this.handler = handler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShiftUpdated event) {
        try {
            InboxOutcome outcome = handler.handle(toCommand(event));
            if (outcome.duplicate()) {
                log.debug("shift-updated {} already processed by AI Forecasting", outcome.eventId());
            } else {
                log.info("AI Forecasting applied shift-updated {} for session {} (assessment {})",
                        outcome.eventId(), event.sessionId(),
                        outcome.assessmentId() != null ? outcome.assessmentId() : "unchanged");
            }
        } catch (RuntimeException e) {
            // El hecho de negocio ya ocurrió; esto solo desincroniza el modelo de
            // lectura de AI Forecasting.
            log.warn("AI Forecasting could not process shift-updated for session {}: {}",
                    event.sessionId(), e.toString());
        }
    }

    /**
     * La zona viaja como texto porque aquí es un identificador opaco que solo se
     * compara por igualdad. La identidad canónica es el id de {@code plant_zones}
     * —Plant Configuration es su dueño desde el PR #6—, así que se usa tal cual
     * en decimal. Cualquier otro productor de snapshots debe usar el mismo
     * formato: si no coincide, la búsqueda de candidatos de rotación no
     * encontraría a nadie en la zona y no habría ningún error que lo delatara.
     */
    private static ShiftUpdatedCommand toCommand(ShiftUpdated e) {
        return new ShiftUpdatedCommand(
                e.envelope().eventId(),
                e.envelope().correlationId(),
                e.envelope().organizationId(),
                e.workerId(),
                e.sessionId(),
                e.newZoneId() != null ? String.valueOf(e.newZoneId()) : null,
                e.newPositionId(),
                null);
    }
}
