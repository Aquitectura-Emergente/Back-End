package com.emsafe.devicemanagement.interfaces.mqtt;

import com.emsafe.devicemanagement.application.DeviceConnectivityService;
import com.emsafe.devicemanagement.application.PlugCommandService;
import com.emsafe.devicemanagement.application.TelemetryIngestionService;
import com.emsafe.devicemanagement.application.TelemetryIngestionService.IncomingReading;
import com.emsafe.devicemanagement.application.TelemetryIngestionService.IngestionResult;
import com.emsafe.devicemanagement.application.TelemetryIngestionService.TelemetryBatch;
import com.emsafe.devicemanagement.domain.model.CapturedAtSource;
import com.emsafe.devicemanagement.domain.model.ConnectivityStatus;
import com.emsafe.devicemanagement.domain.model.DeviceSerial;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.model.MagneticFluxDensity;
import com.emsafe.devicemanagement.domain.model.OrganizationCode;
import com.emsafe.devicemanagement.domain.model.PlugPower;
import com.emsafe.devicemanagement.domain.model.PlugState;
import com.emsafe.devicemanagement.domain.model.PlugStateSource;
import com.emsafe.devicemanagement.domain.model.ReadingQuality;
import com.emsafe.devicemanagement.domain.model.SyncOrigin;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.DeviceStatusMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.EdgeStatusMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.PlugStateMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.TelemetryMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractViolation;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.MqttContract;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.MqttContract.Topic;
import com.emsafe.shared.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

import static com.emsafe.devicemanagement.infrastructure.mqtt.contract.MqttContract.enumValue;
import static com.emsafe.devicemanagement.infrastructure.mqtt.contract.MqttContract.instant;

/**
 * Consumidor MQTT: traduce cada mensaje del contrato a un caso de uso. Un
 * mensaje que no cumple el contrato se descarta con un aviso; no se reintenta
 * porque volvería a fallar igual.
 */
@Component
@RequiredArgsConstructor
public class MqttMessageRouter {
    private static final Logger log = LoggerFactory.getLogger(MqttMessageRouter.class);

    private final TelemetryIngestionService ingestion;
    private final DeviceConnectivityService connectivity;
    private final PlugCommandService plugs;

    public void route(String topicName, byte[] payload) {
        Topic topic = MqttContract.parseTopic(topicName);
        try {
            switch (topic.kind()) {
                case TELEMETRY -> telemetry(topic, MqttContract.parseTelemetry(payload));
                case DEVICE_STATUS -> deviceStatus(topic, MqttContract.parseDeviceStatus(payload));
                case EDGE_STATUS -> edgeStatus(topic, MqttContract.parseEdgeStatus(payload));
                case PLUG_STATE -> plugState(topic, MqttContract.parsePlugState(payload));
                case UNKNOWN -> log.debug("Ignoring message on {}", topicName);
            }
        } catch (ContractViolation | BadRequestException e) {
            log.warn("Discarding message on {}: {}", topicName, e.getMessage());
        }
    }

    private void telemetry(Topic topic, TelemetryMessage m) {
        sameId(topic, m.deviceId(), "deviceId");
        DeviceType type = enumValue(DeviceType.class, m.deviceType(), "deviceType");
        if (type == DeviceType.SMART_PLUG) throw new ContractViolation("deviceType SMART_PLUG has no telemetry");
        List<IncomingReading> readings = m.readings().stream().map(r -> new IncomingReading(
                r.sequence(),
                instant(r.capturedAt(), "capturedAt"),
                enumValue(CapturedAtSource.class, r.capturedAtSource(), "capturedAtSource"),
                instant(r.receivedAt(), "receivedAt"),
                new MagneticFluxDensity(BigDecimal.valueOf(r.magneticFluxDensityUT())),
                enumValue(SyncOrigin.class, r.origin(), "origin"),
                enumValue(ReadingQuality.class, r.quality(), "quality"))).toList();
        OrganizationCode organization;
        try {
            organization = new OrganizationCode(m.organizationId());
        } catch (BadRequestException e) {
            throw new ContractViolation("invalid organizationId: " + m.organizationId());
        }
        IngestionResult result = ingestion.ingest(new TelemetryBatch(m.messageId(), organization, m.edgeNodeId(),
                DeviceSerial.of(m.deviceId()), type, readings));
        if (result.accepted() && result.stored() > 0) {
            log.debug("Stored {} reading(s) of {} from {}", result.stored(), m.deviceId(), m.edgeNodeId());
        }
    }

    private void deviceStatus(Topic topic, DeviceStatusMessage m) {
        sameId(topic, m.deviceId(), "deviceId");
        connectivity.recordDeviceStatus(m.deviceId(), m.edgeNodeId(),
                enumValue(ConnectivityStatus.class, m.connectivity(), "connectivity"),
                instant(m.changedAt(), "changedAt"), m.firmwareVersion(), m.batteryLevel());
    }

    private void edgeStatus(Topic topic, EdgeStatusMessage m) {
        connectivity.recordEdgeStatus(topic.id(), enumValue(ConnectivityStatus.class, m.status(), "status"),
                m.changedAt() == null ? null : instant(m.changedAt(), "changedAt"));
    }

    private void plugState(Topic topic, PlugStateMessage m) {
        sameId(topic, m.plugId(), "plugId");
        plugs.recordState(m.plugId(), new PlugState(enumValue(PlugPower.class, m.state(), "state"), m.commandId(),
                enumValue(PlugStateSource.class, m.source(), "source"), instant(m.changedAt(), "changedAt")));
    }

    private static void sameId(Topic topic, String payloadId, String field) {
        if (!topic.id().equals(payloadId)) {
            throw new ContractViolation(field + " " + payloadId + " does not match the topic " + topic.id());
        }
    }
}
