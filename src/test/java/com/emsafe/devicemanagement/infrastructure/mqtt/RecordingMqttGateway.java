package com.emsafe.devicemanagement.infrastructure.mqtt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Doble del broker: guarda lo publicado y permite simular mensajes entrantes. */
public class RecordingMqttGateway implements MqttGateway {
    public record Published(String topic, byte[] payload, int qos, boolean retained) {
    }

    public final List<Published> published = new CopyOnWriteArrayList<>();
    public final List<String> subscribed = new ArrayList<>();
    public final List<String> unsubscribed = new ArrayList<>();
    public volatile boolean accept = true;
    private final List<Runnable> listeners = new ArrayList<>();
    private BiConsumer<String, byte[]> handler = (t, p) -> { };

    @Override
    public boolean publish(String topic, byte[] payload, int qos, boolean retained) {
        if (!accept) return false;
        published.add(new Published(topic, payload, qos, retained));
        return true;
    }

    @Override
    public void subscribe(List<String> topicFilters, int qos) {
        subscribed.addAll(topicFilters);
    }

    @Override
    public void unsubscribe(List<String> topicFilters) {
        unsubscribed.addAll(topicFilters);
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void onConnected(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public void onMessage(BiConsumer<String, byte[]> handler) {
        this.handler = handler;
    }

    public void connect() {
        listeners.forEach(Runnable::run);
    }

    public void deliver(String topic, byte[] payload) {
        handler.accept(topic, payload);
    }

    public void clear() {
        published.clear();
    }
}
