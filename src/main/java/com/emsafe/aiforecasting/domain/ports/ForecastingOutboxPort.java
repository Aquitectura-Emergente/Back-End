package com.emsafe.aiforecasting.domain.ports;

import java.time.Instant;
import java.util.Optional;

/**
 * Outbox transaccional ({@code af_outbox}). La escritura ocurre en la misma transacción
 * que el cambio de estado del agregado.
 */
public interface ForecastingOutboxPort {

    void append(OutboxEvent event);

    /** Eventos con {@code publishedAt} nulo, los más antiguos primero. */
    PageResult<OutboxEvent> findPending(int page, int size);

    Optional<OutboxEvent> findById(String eventId);

    /**
     * Marca el evento como publicado. Idempotente: si ya estaba publicado conserva la fecha
     * original. Devuelve vacío si el evento no existe.
     */
    Optional<OutboxEvent> acknowledge(String eventId, Instant publishedAt);
}
