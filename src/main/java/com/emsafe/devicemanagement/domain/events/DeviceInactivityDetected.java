package com.emsafe.devicemanagement.domain.events;

import java.time.Instant;

public record DeviceInactivityDetected(String deviceId, Long organizationId, Instant lastSeenAt,
                                       Instant occurredAt) implements DeviceDomainEvent {
}
