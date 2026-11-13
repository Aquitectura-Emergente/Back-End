package com.emsafe.devicemanagement.domain.events;

import com.emsafe.devicemanagement.domain.model.SyncOrigin;

import java.time.Instant;

/** Un mensaje de telemetría con lecturas nuevas quedó guardado y enrutado. */
public record TelemetryReadingsSynchronized(String deviceId, Long organizationId, String edgeNodeId,
                                            String messageId, SyncOrigin origin, int newReadings,
                                            int saturatedReadings, Instant firstCapturedAt,
                                            Instant lastCapturedAt, Instant occurredAt) implements DeviceDomainEvent {
}
