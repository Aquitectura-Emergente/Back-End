package com.emsafe.devicemanagement.domain.repositories;

import com.emsafe.devicemanagement.domain.events.DeviceDomainEvent;

/** Guarda el evento en la misma transacción que el cambio; un relay lo publica después. */
public interface DomainEventOutbox {
    void append(DeviceDomainEvent event);
}
