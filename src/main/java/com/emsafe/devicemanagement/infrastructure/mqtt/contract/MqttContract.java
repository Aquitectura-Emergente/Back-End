package com.emsafe.devicemanagement.infrastructure.mqtt.contract;

import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.domain.model.PlugCommand;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.DeviceConfigMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.DeviceStatusMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.EdgeStatusMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.PlugCommandMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.PlugStateMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.TelemetryMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tópicos y formato del contrato MQTT. Es el único lugar que conoce el JSON;
 * hacia dentro solo salen mensajes validados.
 */
public final class MqttContract {
    public static final String SCHEMA_VERSION = "1.0";
    public static final int QOS = 1;
    /** Suscripciones compartidas: cada mensaje lo procesa una sola réplica de ingesta. */
    public static final List<String> SHARED_SUBSCRIPTIONS = List.of(
            "$share/ingestion/iot/+/telemetry",
            "$share/ingestion/iot/+/status",
            "$share/ingestion/edge/+/status",
            "$share/ingestion/iot/+/state");
    /**
     * Las suscripciones compartidas no reciben retained; estas se suscriben un
     * momento al conectar para leer el estado vigente.
     */
    public static final List<String> RETAINED_SYNC_SUBSCRIPTIONS = List.of(
            "iot/+/status", "edge/+/status", "iot/+/state");

    private static final Pattern SUPPORTED_SCHEMA = Pattern.compile("1\\.\\d+");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private MqttContract() {
    }

    public enum TopicKind { TELEMETRY, DEVICE_STATUS, PLUG_STATE, EDGE_STATUS, UNKNOWN }

    public record Topic(TopicKind kind, String id) {
    }

    public static Topic parseTopic(String topic) {
        String[] parts = topic == null ? new String[0] : topic.split("/");
        if (parts.length == 3 && !parts[1].isBlank()) {
            if ("iot".equals(parts[0])) {
                return switch (parts[2]) {
                    case "telemetry" -> new Topic(TopicKind.TELEMETRY, parts[1]);
                    case "status" -> new Topic(TopicKind.DEVICE_STATUS, parts[1]);
                    case "state" -> new Topic(TopicKind.PLUG_STATE, parts[1]);
                    default -> new Topic(TopicKind.UNKNOWN, parts[1]);
                };
            }
            if ("edge".equals(parts[0]) && "status".equals(parts[2])) return new Topic(TopicKind.EDGE_STATUS, parts[1]);
        }
        return new Topic(TopicKind.UNKNOWN, null);
    }

    public static String configTopic(String deviceId) {
        return "iot/" + deviceId + "/config";
    }

    public static String commandTopic(String plugId) {
        return "iot/" + plugId + "/command";
    }

    // ─── Entrantes ────────────────────────────────────────────────────────────

    public static TelemetryMessage parseTelemetry(byte[] payload) {
        TelemetryMessage m = read(payload, TelemetryMessage.class);
        requireSchema(m.schemaVersion());
        require(m.messageId(), "messageId");
        require(m.organizationId(), "organizationId");
        require(m.edgeNodeId(), "edgeNodeId");
        require(m.deviceId(), "deviceId");
        require(m.deviceType(), "deviceType");
        if (m.readings() == null || m.readings().isEmpty()) throw new ContractViolation("readings is empty");
        for (var r : m.readings()) {
            if (r == null || r.sequence() == null || r.sequence() < 0) throw new ContractViolation("invalid sequence");
            instant(r.capturedAt(), "capturedAt");
            instant(r.receivedAt(), "receivedAt");
            require(r.capturedAtSource(), "capturedAtSource");
            require(r.origin(), "origin");
            require(r.quality(), "quality");
            if (r.magneticFluxDensityUT() == null || r.magneticFluxDensityUT().isNaN()
                    || r.magneticFluxDensityUT() < 0) {
                throw new ContractViolation("invalid magneticFluxDensityUT");
            }
        }
        return m;
    }

    public static DeviceStatusMessage parseDeviceStatus(byte[] payload) {
        DeviceStatusMessage m = read(payload, DeviceStatusMessage.class);
        requireSchema(m.schemaVersion());
        require(m.deviceId(), "deviceId");
        require(m.connectivity(), "connectivity");
        instant(m.changedAt(), "changedAt");
        return m;
    }

    public static EdgeStatusMessage parseEdgeStatus(byte[] payload) {
        EdgeStatusMessage m = read(payload, EdgeStatusMessage.class);
        require(m.status(), "status");
        if (m.changedAt() != null) instant(m.changedAt(), "changedAt");
        return m;
    }

    public static PlugStateMessage parsePlugState(byte[] payload) {
        PlugStateMessage m = read(payload, PlugStateMessage.class);
        requireSchema(m.schemaVersion());
        require(m.plugId(), "plugId");
        require(m.state(), "state");
        require(m.source(), "source");
        instant(m.changedAt(), "changedAt");
        return m;
    }

    /** Convierte una fecha del contrato (ISO-8601 UTC). */
    public static Instant instant(String value, String field) {
        if (value == null) throw new ContractViolation(field + " is required");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ContractViolation(field + " is not an ISO-8601 UTC instant");
        }
    }

    /** Enum del contrato; un valor desconocido no cumple el contrato. */
    public static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ContractViolation("unknown " + field + ": " + value);
        }
    }

    // ─── Salientes ────────────────────────────────────────────────────────────

    public static DeviceConfigMessage configMessage(DeviceConfigChanged c) {
        return new DeviceConfigMessage(SCHEMA_VERSION, c.deviceId(), c.configVersion(), c.alertThresholdUT(),
                c.definedBy().name(), utc(c.updatedAt()));
    }

    public static PlugCommandMessage commandMessage(PlugCommand c) {
        return new PlugCommandMessage(SCHEMA_VERSION, c.getCommandId(), c.getPlugSerial(), c.getAction().name(),
                c.getRequestedBy(), utc(c.getRequestedAt()));
    }

    public static byte[] write(Object message) {
        try {
            return JSON.writeValueAsBytes(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize MQTT message", e);
        }
    }

    private static String utc(Instant at) {
        return at == null ? null : at.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static <T> T read(byte[] payload, Class<T> type) {
        if (payload == null || payload.length == 0) throw new ContractViolation("empty payload");
        try {
            T value = JSON.readValue(payload, type);
            if (value == null) throw new ContractViolation("empty payload");
            return value;
        } catch (IOException e) {
            throw new ContractViolation("malformed " + type.getSimpleName() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        }
    }

    private static void requireSchema(String version) {
        if (version == null || !SUPPORTED_SCHEMA.matcher(version).matches()) {
            throw new ContractViolation("unsupported schemaVersion: " + version);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new ContractViolation(field + " is required");
    }
}
