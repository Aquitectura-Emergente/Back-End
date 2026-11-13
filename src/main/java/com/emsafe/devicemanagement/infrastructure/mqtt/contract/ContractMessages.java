package com.emsafe.devicemanagement.infrastructure.mqtt.contract;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mensajes del contrato MQTT, esquema 1.0 (rev. 1.0.1). Los nombres de los
 * campos son exactamente los del contrato: los tests los comparan con los
 * ejemplos de los README de los Edges.
 */
public final class ContractMessages {
    private ContractMessages() {
    }

    /** {@code iot/{deviceId}/telemetry} */
    public record TelemetryMessage(String schemaVersion, String messageId, String organizationId, String edgeNodeId,
                                   String deviceId, String deviceType, String publishedAt,
                                   List<TelemetryReadingMessage> readings) {
    }

    public record TelemetryReadingMessage(Long sequence, String capturedAt, String capturedAtSource,
                                          String receivedAt, Double magneticFluxDensityUT, String origin,
                                          String quality) {
    }

    /** {@code iot/{deviceId}/status} (retained) */
    public record DeviceStatusMessage(String schemaVersion, String deviceId, String edgeNodeId, String connectivity,
                                      String changedAt, String firmwareVersion, Integer batteryLevel) {
    }

    /** {@code edge/{edgeNodeId}/status} (retained; el Last Will no trae changedAt) */
    public record EdgeStatusMessage(String status, String changedAt) {
    }

    /** {@code iot/{deviceId}/config} (retained, lo publica el backend) */
    public record DeviceConfigMessage(String schemaVersion, String deviceId, int configVersion,
                                      BigDecimal alertThresholdUT, String definedBy, String updatedAt) {
    }

    /** {@code iot/{plugId}/command} (lo publica el backend) */
    public record PlugCommandMessage(String schemaVersion, String commandId, String plugId, String action,
                                     String requestedBy, String requestedAt) {
    }

    /** {@code iot/{plugId}/state} (retained) */
    public record PlugStateMessage(String schemaVersion, String plugId, String state, String commandId,
                                   String source, String changedAt) {
    }
}
