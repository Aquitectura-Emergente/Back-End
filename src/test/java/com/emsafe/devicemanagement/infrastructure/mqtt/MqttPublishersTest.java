package com.emsafe.devicemanagement.infrastructure.mqtt;

import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.domain.model.ConfigDefinedBy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MqttPublishersTest {

    @Test
    void theConfigIsPublishedRetainedWithQos1() {
        RecordingMqttGateway mqtt = new RecordingMqttGateway();
        boolean ok = new MqttDeviceConfigPublisher(mqtt).publish(new DeviceConfigChanged("EMSafe-DOS-01", 7L, 2,
                new BigDecimal("250.0"), ConfigDefinedBy.SSO_RESPONSIBLE, 1000, 300,
                Instant.parse("2026-11-12T14:00:00Z")));
        assertThat(ok).isTrue();
        RecordingMqttGateway.Published p = mqtt.published.get(0);
        assertThat(p.topic()).isEqualTo("iot/EMSafe-DOS-01/config");
        assertThat(p.qos()).isEqualTo(1);
        assertThat(p.retained()).isTrue();
        assertThat(new String(p.payload(), StandardCharsets.UTF_8)).contains("\"configVersion\":2");
    }

    @Test
    void aBrokerFailureIsReportedSoTheOutboxRetries() {
        RecordingMqttGateway mqtt = new RecordingMqttGateway();
        mqtt.accept = false;
        assertThat(new MqttDeviceConfigPublisher(mqtt).publish(new DeviceConfigChanged("D", 7L, 1,
                new BigDecimal("300.0"), ConfigDefinedBy.SYSTEM_DEFAULT, 1000, 300, Instant.now()))).isFalse();
    }
}
