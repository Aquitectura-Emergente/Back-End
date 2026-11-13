package com.emsafe.devicemanagement.domain.events;

import com.emsafe.devicemanagement.domain.model.ConnectivityStatus;

import java.time.Instant;

/** {@code reason}: STATUS (lo informó el Edge), TELEMETRY (volvió a llegar una lectura) o INACTIVITY. */
public record DeviceConnectivityChanged(String deviceId, Long organizationId, ConnectivityStatus connectivity,
                                        ConnectivityStatus previous, Instant changedAt, String edgeNodeId,
                                        String reason, Instant occurredAt) implements DeviceDomainEvent {
}
