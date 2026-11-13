package com.emsafe.devicemanagement.domain.events;

import com.emsafe.devicemanagement.domain.model.PlugAction;

import java.time.Instant;

public record PlugCommandRequested(String plugId, Long organizationId, String commandId, PlugAction action,
                                   Instant requestedAt, Long requestedByUserId) implements DeviceDomainEvent {
    @Override
    public Instant occurredAt() {
        return requestedAt;
    }
}
