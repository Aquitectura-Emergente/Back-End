package com.emsafe.devicemanagement.interfaces.mqtt;

import com.emsafe.devicemanagement.infrastructure.mqtt.MqttGateway;
import com.emsafe.devicemanagement.infrastructure.mqtt.MqttProperties;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.MqttContract;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Suscripciones de la ingesta (perfil {@code ingestion}). En cada conexión se
 * suscribe con {@code $share} para repartir los mensajes entre réplicas y, por
 * un momento, sin compartir, para leer los estados retained que las
 * suscripciones compartidas no reciben.
 */
@Component
@Profile("ingestion")
@RequiredArgsConstructor
public class MqttIngestionSubscriber {
    private final MqttGateway mqtt;
    private final MqttMessageRouter router;
    private final MqttProperties properties;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-retained-sync");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void register() {
        mqtt.onMessage(router::route);
        mqtt.onConnected(this::subscribe);
    }

    void subscribe() {
        mqtt.subscribe(MqttContract.SHARED_SUBSCRIPTIONS, MqttContract.QOS);
        mqtt.subscribe(MqttContract.RETAINED_SYNC_SUBSCRIPTIONS, MqttContract.QOS);
        timer.schedule(() -> mqtt.unsubscribe(MqttContract.RETAINED_SYNC_SUBSCRIPTIONS),
                properties.getRetainedSyncMs(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }
}
