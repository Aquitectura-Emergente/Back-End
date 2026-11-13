package com.emsafe.devicemanagement.infrastructure.mqtt;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Cliente MQTT v5 (Paho). La aplicación arranca aunque el broker no esté: la
 * primera conexión se reintenta con espera creciente y, una vez conectado,
 * Paho reconecta solo. Los mensajes entrantes se reparten por tópico desde el
 * callback global, porque los listeners por suscripción no reconocen los
 * tópicos de una suscripción compartida ($share).
 */
@Component
@ConditionalOnProperty(name = "emsafe.mqtt.enabled", havingValue = "true")
public class PahoMqttGateway implements MqttGateway, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PahoMqttGateway.class);

    private final com.emsafe.devicemanagement.infrastructure.mqtt.MqttProperties config;
    private final List<Runnable> connectionListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-connect");
        t.setDaemon(true);
        return t;
    });
    private volatile BiConsumer<String, byte[]> messageHandler = (topic, payload) -> { };
    private volatile MqttAsyncClient client;
    private volatile boolean running;

    public PahoMqttGateway(com.emsafe.devicemanagement.infrastructure.mqtt.MqttProperties config) {
        this.config = config;
    }

    @Override
    public void start() {
        running = true;
        try {
            client = new MqttAsyncClient(config.serverUri(), config.getClientId(), new MemoryPersistence());
            client.setCallback(new Callback());
        } catch (MqttException e) {
            log.error("Invalid MQTT configuration ({}): {}", config.serverUri(), e.getMessage());
            return;
        }
        scheduler.execute(() -> connect(1000));
    }

    private void connect(long delayMs) {
        if (!running) return;
        try {
            client.connect(options()).waitForCompletion(15000);
            log.info("Connected to MQTT broker {}", config.serverUri());
        } catch (MqttException e) {
            long next = Math.min(delayMs * 2, config.getMaxReconnectDelayMs());
            log.warn("MQTT broker {} not available ({}); retrying in {} ms", config.serverUri(), e.getMessage(), delayMs);
            scheduler.schedule(() -> connect(next), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private MqttConnectionOptions options() {
        MqttConnectionOptions o = new MqttConnectionOptions();
        o.setCleanStart(true);
        o.setAutomaticReconnect(true);
        o.setAutomaticReconnectDelay(1, (int) Math.max(1, config.getMaxReconnectDelayMs() / 1000));
        o.setConnectionTimeout(10);
        o.setKeepAliveInterval(30);
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            o.setUserName(config.getUsername());
            if (config.getPassword() != null) o.setPassword(config.getPassword().getBytes(StandardCharsets.UTF_8));
        }
        return o;
    }

    @Override
    public void publish(String topic, byte[] payload, int qos, boolean retained) {
        MqttAsyncClient c = client;
        if (c == null || !c.isConnected()) {
            throw new MqttPublishException("not connected to broker " + config.serverUri());
        }
        try {
            IMqttToken token = c.publish(topic, payload, qos, retained);
            token.waitForCompletion(config.getPublishTimeoutMs());
            if (!token.isComplete()) {
                throw new MqttPublishException("no confirmation from the broker within "
                        + config.getPublishTimeoutMs() + " ms");
            }
            if (token.getException() != null) {
                throw new MqttPublishException(token.getException().getMessage(), token.getException());
            }
        } catch (MqttException e) {
            throw new MqttPublishException(e.getMessage() + " (reason code " + e.getReasonCode() + ")", e);
        }
    }

    @Override
    public void subscribe(List<String> topicFilters, int qos) {
        MqttAsyncClient c = client;
        if (c == null || !c.isConnected() || topicFilters.isEmpty()) return;
        int[] qosArray = new int[topicFilters.size()];
        java.util.Arrays.fill(qosArray, qos);
        try {
            c.subscribe(topicFilters.toArray(String[]::new), qosArray).waitForCompletion(10000);
            log.info("Subscribed to {}", topicFilters);
        } catch (MqttException e) {
            log.warn("Could not subscribe to {}: {}", topicFilters, e.getMessage());
        }
    }

    @Override
    public void unsubscribe(List<String> topicFilters) {
        MqttAsyncClient c = client;
        if (c == null || !c.isConnected() || topicFilters.isEmpty()) return;
        try {
            c.unsubscribe(topicFilters.toArray(String[]::new));
        } catch (MqttException e) {
            log.debug("Could not unsubscribe from {}: {}", topicFilters, e.getMessage());
        }
    }

    @Override
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    @Override
    public void onConnected(Runnable listener) {
        connectionListeners.add(listener);
        if (isConnected()) scheduler.execute(listener);
    }

    @Override
    public void onMessage(BiConsumer<String, byte[]> handler) {
        this.messageHandler = handler;
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        MqttAsyncClient c = client;
        if (c == null) return;
        try {
            if (c.isConnected()) c.disconnect().waitForCompletion(2000);
            c.close();
        } catch (MqttException e) {
            log.debug("MQTT close: {}", e.getMessage());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private final class Callback implements MqttCallback {
        @Override
        public void disconnected(MqttDisconnectResponse response) {
            log.warn("Disconnected from MQTT broker: {}", response == null ? "" : response.getReasonString());
        }

        @Override
        public void mqttErrorOccurred(MqttException exception) {
            log.warn("MQTT error: {}", exception.getMessage());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            try {
                messageHandler.accept(topic, message.getPayload());
            } catch (RuntimeException e) {
                // Una excepción aquí cortaría la conexión: se registra y se sigue.
                log.error("Error handling MQTT message on {}: {}", topic, e.toString());
            }
        }

        @Override
        public void deliveryComplete(IMqttToken token) {
        }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            // Los listeners suscriben; se ejecutan fuera del hilo de Paho.
            connectionListeners.forEach(scheduler::execute);
        }

        @Override
        public void authPacketArrived(int reasonCode, MqttProperties properties) {
        }
    }
}
