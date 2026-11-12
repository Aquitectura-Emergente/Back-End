package com.emsafe.occupationalhealth.application.events;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * El sobre común de todo mensaje del contexto (informe §5.6.3): eventId,
 * schemaVersion, organizationId, occurredAt, correlationId y causationId.
 *
 * Es el mismo sobre que usa Personal Dosimetry, y es lo que permitirá mover
 * estos eventos a un broker sin cambiar su contrato. Hoy se publican en proceso
 * con ApplicationEventPublisher, porque la solución no tiene broker: ver la nota
 * de ApproveMitigationActionCommandHandler.
 */
public record EventEnvelope(
        String eventId,
        int schemaVersion,
        Long organizationId,
        LocalDateTime occurredAt,
        String correlationId,
        String causationId) {

    private static final int CURRENT_SCHEMA_VERSION = 1;

    /** Sobre de un hecho que origina una cadena nueva. */
    public static EventEnvelope of(Long organizationId) {
        String id = UUID.randomUUID().toString();
        return new EventEnvelope(id, CURRENT_SCHEMA_VERSION, organizationId,
                LocalDateTime.now(), id, null);
    }

    /**
     * Sobre de un hecho causado por otro: conserva el correlationId de la cadena
     * y apunta al evento que lo provocó.
     */
    public static EventEnvelope causedBy(Long organizationId, EventEnvelope cause) {
        return new EventEnvelope(UUID.randomUUID().toString(), CURRENT_SCHEMA_VERSION,
                organizationId, LocalDateTime.now(), cause.correlationId(), cause.eventId());
    }
}
