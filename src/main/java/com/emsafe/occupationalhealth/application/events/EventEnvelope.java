package com.emsafe.occupationalhealth.application.events;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * El sobre común de todo mensaje del contexto (informe §5.6.3): eventId,
 * schemaVersion, organizationId, occurredAt, correlationId y causationId.
 *
 * Es el mismo sobre que usa Personal Dosimetry, y es lo que permitirá mover
 * estos eventos a un broker sin cambiar su contrato. Hoy se publican en proceso
 * con ApplicationEventPublisher, porque la solución no tiene broker: ver la nota
 * de ApproveMitigationActionCommandHandler.
 *
 * <p>{@code occurredAt} es la hora <b>UTC</b>, sin zona. Hasta el 2026-11-22 se
 * sellaba con {@code LocalDateTime.now()}, que usa la zona de la JVM: en una
 * laptop en Perú (UTC−5) los hechos de este contexto quedaban 5 h detrás de los
 * de Personal Dosimetry, que el relay ya convierte a UTC. Ahora los dos lados
 * hablan la misma hora.</p>
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
                nowUtc(), id, null);
    }

    /**
     * Sobre de un hecho causado por otro: conserva el correlationId de la cadena
     * y apunta al evento que lo provocó.
     */
    public static EventEnvelope causedBy(Long organizationId, EventEnvelope cause) {
        return new EventEnvelope(UUID.randomUUID().toString(), CURRENT_SCHEMA_VERSION,
                organizationId, nowUtc(), cause.correlationId(), cause.eventId());
    }

    /** La hora actual en UTC, la misma base que usa el relay de Personal Dosimetry. */
    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
