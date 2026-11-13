package com.emsafe.devicemanagement.interfaces.mqtt;

import com.emsafe.devicemanagement.infrastructure.mqtt.MqttProperties;
import com.emsafe.devicemanagement.infrastructure.mqtt.RecordingMqttGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MqttIngestionSubscriberTest {

    @Test
    void onEveryConnectionItSubscribesSharedAndBrieflyReadsTheRetainedStates() throws Exception {
        RecordingMqttGateway mqtt = new RecordingMqttGateway();
        MqttMessageRouter router = mock(MqttMessageRouter.class);
        MqttProperties properties = new MqttProperties();
        properties.setRetainedSyncMs(50);
        MqttIngestionSubscriber subscriber = new MqttIngestionSubscriber(mqtt, router, properties);
        subscriber.register();
        mqtt.connect();

        assertThat(mqtt.subscribed).containsExactly(
                "$share/ingestion/iot/+/telemetry", "$share/ingestion/iot/+/status",
                "$share/ingestion/edge/+/status", "$share/ingestion/iot/+/state",
                "iot/+/status", "edge/+/status", "iot/+/state");
        Thread.sleep(300);
        assertThat(mqtt.unsubscribed).containsExactly("iot/+/status", "edge/+/status", "iot/+/state");

        mqtt.deliver("iot/X/telemetry", new byte[]{1});
        verify(router).route("iot/X/telemetry", new byte[]{1});
        subscriber.shutdown();
    }
}
