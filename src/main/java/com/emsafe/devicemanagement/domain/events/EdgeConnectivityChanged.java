package com.emsafe.devicemanagement.domain.events;

import com.emsafe.devicemanagement.domain.model.ConnectivityStatus;
import com.emsafe.devicemanagement.domain.model.EdgeSegment;

import java.time.Instant;

public record EdgeConnectivityChanged(String edgeNodeId, Long organizationId, EdgeSegment segment,
                                      ConnectivityStatus connectivity, Instant changedAt) implements DeviceDomainEvent {
    @Override
    public Instant occurredAt() {
        return changedAt;
    }
}
