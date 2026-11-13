package com.emsafe.devicemanagement.domain.events;

import com.emsafe.devicemanagement.domain.model.PlugPower;
import com.emsafe.devicemanagement.domain.model.PlugStateSource;

import java.time.Instant;

public record PlugStateReported(String plugId, Long organizationId, PlugPower state, String commandId,
                                PlugStateSource source, Instant changedAt) implements DeviceDomainEvent {
    @Override
    public Instant occurredAt() {
        return changedAt;
    }
}
