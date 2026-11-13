package com.emsafe.devicemanagement.infrastructure.mqtt;

import com.emsafe.devicemanagement.application.ports.PublicationFailedException;
import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.domain.model.ConfigDefinedBy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MqttPublishersTest {

    @Test
    void theConfigIsPublishedRetainedWithQos1() {
        RecordingMqttGateway mqtt = new RecordingMqttGateway();
        new MqttDeviceConfigPublisher(mqtt).publish(config(2));
        RecordingMqttGateway.Published p = mqtt.published.get(0);
        assertThat(p.topic()).isEqualTo("iot/EMSafe-DOS-01/config");
        assertThat(p.qos()).isEqualTo(1);
        assertThat(p.retained()).isTrue();
        assertThat(new String(p.payload(), StandardCharsets.UTF_8)).contains("\"configVersion\":2");
    }

    @Test
    void aBrokerFailureCarriesTheTopicAndTheReasonSoTheOutboxRetriesAndLogsIt() {
        RecordingMqttGateway mqtt = new RecordingMqttGateway();
        mqtt.accept = false;
        assertThatThrownBy(() -> new MqttDeviceConfigPublisher(mqtt).publish(config(1)))
                .isInstanceOf(PublicationFailedException.class)
                .hasMessageContaining("iot/EMSafe-DOS-01/config")
                .hasMessageContaining("not connected");
    }

    @Test
    void withMqttDisabledThePublicationFailsWithAnExplicitReason() {
        assertThatThrownBy(() -> new MqttDeviceConfigPublisher(new DisabledMqttGateway()).publish(config(1)))
                .isInstanceOf(PublicationFailedException.class)
                .hasMessageContaining("emsafe.mqtt.enabled=false");
    }

    private static DeviceConfigChanged config(int version) {
        return new DeviceConfigChanged("EMSafe-DOS-01", 7L, version, new BigDecimal("250.0"),
                ConfigDefinedBy.SSO_RESPONSIBLE, 1000, 300, Instant.parse("2026-11-12T14:00:00Z"));
    }
}
