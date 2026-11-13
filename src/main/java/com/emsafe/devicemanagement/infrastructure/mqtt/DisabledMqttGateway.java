package com.emsafe.devicemanagement.infrastructure.mqtt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiConsumer;

/** Con {@code emsafe.mqtt.enabled=false} no se publica nada: el outbox conserva la configuración pendiente. */
@Component
@ConditionalOnProperty(name = "emsafe.mqtt.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledMqttGateway implements MqttGateway {
    private static final Logger log = LoggerFactory.getLogger(DisabledMqttGateway.class);

    @Override
    public boolean publish(String topic, byte[] payload, int qos, boolean retained) {
        log.debug("MQTT disabled; not publishing to {}", topic);
        return false;
    }

    @Override
    public void subscribe(List<String> topicFilters, int qos) {
    }

    @Override
    public void unsubscribe(List<String> topicFilters) {
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public void onConnected(Runnable listener) {
    }

    @Override
    public void onMessage(BiConsumer<String, byte[]> handler) {
    }
}
