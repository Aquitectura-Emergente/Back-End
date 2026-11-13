package com.emsafe.devicemanagement.infrastructure.mqtt;

import java.util.List;
import java.util.function.BiConsumer;

/** Conexión MQTT del backend. Las pruebas usan un doble. */
public interface MqttGateway {
    /** @return true si el broker confirmó (QoS 1) */
    boolean publish(String topic, byte[] payload, int qos, boolean retained);

    void subscribe(List<String> topicFilters, int qos);

    void unsubscribe(List<String> topicFilters);

    boolean isConnected();

    /** Se ejecuta en cada conexión, incluidas las reconexiones. */
    void onConnected(Runnable listener);

    /** Recibe cada mensaje entrante (tópico, payload). */
    void onMessage(BiConsumer<String, byte[]> handler);
}
