package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.ports.ForecastingOutboxPort;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.shared.exception.ResourceNotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** Marca un evento del outbox como publicado. Idempotente: un segundo ack conserva la fecha original. */
public class AckOutboxEventHandler {

    private final ForecastingOutboxPort outbox;
    private final Clock clock;

    public AckOutboxEventHandler(ForecastingOutboxPort outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public OutboxEvent handle(String eventId) {
        return outbox.acknowledge(eventId, clock.instant())
                .orElseThrow(() -> new ResourceNotFoundException("Outbox event not found: " + eventId));
    }
}
