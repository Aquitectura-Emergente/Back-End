package com.emsafe.devicemanagement.domain.events;

import java.time.Instant;

/** Hecho relevante de Device Management; se publica por el outbox del contexto. */
public interface DeviceDomainEvent {
    Long organizationId();

    Instant occurredAt();

    default String eventType() {
        return getClass().getSimpleName();
    }
}
