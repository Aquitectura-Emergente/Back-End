package com.emsafe.devicemanagement.infrastructure.mqtt.contract;

import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.domain.model.ConfigDefinedBy;
import com.emsafe.devicemanagement.domain.model.ConnectivityStatus;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceSerial;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.model.PlugAction;
import com.emsafe.devicemanagement.domain.model.PlugCommand;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.DeviceConfigMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.DeviceStatusMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.EdgeStatusMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.PlugCommandMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.PlugStateMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.TelemetryMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractMessages.TelemetryReadingMessage;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.MqttContract.TopicKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractFixtures.bytes;
import static com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractFixtures.home;
import static com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractFixtures.industrial;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El formato MQTT contra los ejemplos exactos de los README de los dos Edges:
 * cualquier diferencia de nombres, campos o valores hace fallar estos tests.
 */
class MqttContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Nested
    class Inbound {
        @Test
        void industrialTelemetry() {
            TelemetryMessage m = MqttContract.parseTelemetry(bytes(industrial("telemetry")));
            assertThat(m.schemaVersion()).isEqualTo("1.0");
            assertThat(m.messageId()).isEqualTo("6f1c2a9e-8d2b-4c7e-9a51-2f0b7c3d4e10");
            assertThat(m.organizationId()).isEqualTo("org-demo");
            assertThat(m.edgeNodeId()).isEqualTo("EDGE-PLANTA-01");
            assertThat(m.deviceId()).isEqualTo("EMSafe-DOS-01");
            assertThat(m.deviceType()).isEqualTo("PERSONAL_DOSIMETER");
            assertThat(m.publishedAt()).isEqualTo("2026-11-12T15:04:05Z");
            assertReading(m.readings().get(0));
            assertThat(m.readings()).hasSize(1);
        }

        @Test
        void homeTelemetry() {
            TelemetryMessage m = MqttContract.parseTelemetry(bytes(home("telemetry")));
            assertThat(m.edgeNodeId()).isEqualTo("EDGE-HOGAR-01");
            assertThat(m.deviceType()).isEqualTo("FIXED_SENSOR");
            assertThat(m.organizationId()).isEqualTo("org-demo");
            assertThat(m.deviceId()).isEqualTo("EMSafe-DOS-01");
            assertReading(m.readings().get(0));
        }

        private void assertReading(TelemetryReadingMessage r) {
            assertThat(r.sequence()).isEqualTo(1234L);
            assertThat(r.capturedAt()).isEqualTo("2026-11-12T15:04:04Z");
            assertThat(r.capturedAtSource()).isEqualTo("DEVICE_CLOCK");
            assertThat(r.receivedAt()).isEqualTo("2026-11-12T15:04:04Z");
            assertThat(r.magneticFluxDensityUT()).isEqualTo(219.5);
            assertThat(r.origin()).isEqualTo("REALTIME");
            assertThat(r.quality()).isEqualTo("VALID");
        }

        @Test
        void deviceStatusFromBothEdges() {
            DeviceStatusMessage plant = MqttContract.parseDeviceStatus(bytes(industrial("device-status")));
            DeviceStatusMessage house = MqttContract.parseDeviceStatus(bytes(home("device-status")));
            assertThat(plant).isEqualTo(new DeviceStatusMessage("1.0", "EMSafe-DOS-01", "EDGE-PLANTA-01", "ONLINE",
                    "2026-11-12T15:00:00Z", null, null));
            assertThat(house.edgeNodeId()).isEqualTo("EDGE-HOGAR-01");
            assertThat(MqttContract.enumValue(ConnectivityStatus.class, house.connectivity(), "connectivity"))
                    .isEqualTo(ConnectivityStatus.ONLINE);
        }

        @Test
        void edgeStatusAndLastWillWithoutChangedAt() {
            for (String json : new String[]{industrial("edge-online"), home("edge-online")}) {
                assertThat(MqttContract.parseEdgeStatus(bytes(json)))
                        .isEqualTo(new EdgeStatusMessage("ONLINE", "2026-11-12T15:00:00Z"));
            }
            for (String json : new String[]{industrial("edge-lwt"), home("edge-lwt")}) {
                assertThat(MqttContract.parseEdgeStatus(bytes(json))).isEqualTo(new EdgeStatusMessage("OFFLINE", null));
            }
        }

        @Test
        void plugState() {
            PlugStateMessage m = MqttContract.parsePlugState(bytes(home("plug-state")));
            assertThat(m).isEqualTo(new PlugStateMessage("1.0", "EMSafe-PLUG-01", "OFF",
                    "3b8e1f7a-2c4d-4e9b-8a1f-6d5c4b3a2e10", "USER", "2026-11-12T15:10:01Z"));
        }

        @Test
        void anUnknownMajorVersionOrMissingFieldIsRejected() {
            String v2 = industrial("telemetry").replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"2.0\"");
            assertThatThrownBy(() -> MqttContract.parseTelemetry(bytes(v2))).isInstanceOf(ContractViolation.class);
            String v11 = industrial("telemetry").replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"1.1\"");
            assertThat(MqttContract.parseTelemetry(bytes(v11)).schemaVersion()).isEqualTo("1.1");
            String noOrg = industrial("telemetry").replace("\"organizationId\": \"org-demo\",", "");
            assertThatThrownBy(() -> MqttContract.parseTelemetry(bytes(noOrg))).isInstanceOf(ContractViolation.class);
            assertThatThrownBy(() -> MqttContract.parseTelemetry(bytes("{not json")))
                    .isInstanceOf(ContractViolation.class);
            assertThatThrownBy(() -> MqttContract.enumValue(ConnectivityStatus.class, "SLEEPING", "connectivity"))
                    .isInstanceOf(ContractViolation.class);
        }
    }

    /** Si un Edge renombra, agrega o quita un campo, el conjunto deja de coincidir. */
    @Nested
    class FieldNames {
        @Test
        void everyMessageHasExactlyTheFieldsOfTheExamples() throws Exception {
            assertSameFields(TelemetryMessage.class, industrial("telemetry"));
            assertSameFields(TelemetryMessage.class, home("telemetry"));
            assertSameFields(TelemetryReadingMessage.class,
                    JSON.readTree(industrial("telemetry")).get("readings").get(0).toString());
            assertSameFields(DeviceStatusMessage.class, industrial("device-status"));
            assertSameFields(DeviceStatusMessage.class, home("device-status"));
            assertSameFields(EdgeStatusMessage.class, industrial("edge-online"));
            assertSameFields(DeviceConfigMessage.class, industrial("device-config"));
            assertSameFields(PlugCommandMessage.class, home("plug-command"));
            assertSameFields(PlugStateMessage.class, home("plug-state"));
        }

        private void assertSameFields(Class<? extends Record> type, String json) throws Exception {
            Set<String> keys = new HashSet<>();
            JSON.readTree(json).fieldNames().forEachRemaining(keys::add);
            Set<String> components = new HashSet<>();
            Arrays.stream(type.getRecordComponents()).forEach(c -> components.add(c.getName()));
            assertThat(components).as(type.getSimpleName()).isEqualTo(keys);
        }
    }

    @Nested
    class Outbound {
        @Test
        void theConfigIsExactlyTheReadmeExample() throws Exception {
            DeviceConfigChanged event = new DeviceConfigChanged("EMSafe-DOS-01", 7L, 3, new BigDecimal("300.0"),
                    ConfigDefinedBy.SSO_RESPONSIBLE, 1000, 300, Instant.parse("2026-11-12T14:00:00Z"));
            String json = new String(MqttContract.write(MqttContract.configMessage(event)), StandardCharsets.UTF_8);
            JSONAssert.assertEquals(industrial("device-config"), json, JSONCompareMode.STRICT);
            JsonNode tree = JSON.readTree(json);
            assertThat(tree.get("alertThresholdUT").isFloatingPointNumber()).isTrue();
            assertThat(json).contains("\"alertThresholdUT\":300.0");
        }

        @Test
        void thePlugCommandIsExactlyTheReadmeExample() throws Exception {
            Device plug = Device.register(DeviceSerial.of("EMSafe-PLUG-01"), DeviceType.SMART_PLUG, null, null,
                    "admin", Instant.parse("2026-11-12T15:00:00Z"));
            PlugCommand command = plug.requestPlugCommand(PlugAction.OFF, 1L, "user",
                    Instant.parse("2026-11-12T15:10:00.450Z"));
            ReflectionTestUtils.setField(command, "commandId", "3b8e1f7a-2c4d-4e9b-8a1f-6d5c4b3a2e10");
            String json = new String(MqttContract.write(MqttContract.commandMessage(command)), StandardCharsets.UTF_8);
            JSONAssert.assertEquals(home("plug-command"), json, JSONCompareMode.STRICT);
        }

        @Test
        void topicsFollowTheContract() {
            assertThat(MqttContract.configTopic("EMSafe-DOS-01")).isEqualTo("iot/EMSafe-DOS-01/config");
            assertThat(MqttContract.commandTopic("EMSafe-PLUG-01")).isEqualTo("iot/EMSafe-PLUG-01/command");
            assertThat(MqttContract.SHARED_SUBSCRIPTIONS).containsExactly("$share/ingestion/iot/+/telemetry",
                    "$share/ingestion/iot/+/status", "$share/ingestion/edge/+/status", "$share/ingestion/iot/+/state");
            assertThat(MqttContract.parseTopic("iot/EMSafe-DOS-01/telemetry"))
                    .isEqualTo(new MqttContract.Topic(TopicKind.TELEMETRY, "EMSafe-DOS-01"));
            assertThat(MqttContract.parseTopic("iot/EMSafe-PLUG-01/state").kind()).isEqualTo(TopicKind.PLUG_STATE);
            assertThat(MqttContract.parseTopic("iot/EMSafe-DOS-01/status").kind()).isEqualTo(TopicKind.DEVICE_STATUS);
            assertThat(MqttContract.parseTopic("edge/EDGE-HOGAR-01/status"))
                    .isEqualTo(new MqttContract.Topic(TopicKind.EDGE_STATUS, "EDGE-HOGAR-01"));
            assertThat(MqttContract.parseTopic("iot/x/config").kind()).isEqualTo(TopicKind.UNKNOWN);
        }
    }
}
