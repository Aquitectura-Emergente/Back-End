package com.emsafe.devicemanagement.infrastructure.mqtt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code emsafe.mqtt.*}. Por defecto, Mosquitto local sin TLS. */
@Component
@ConfigurationProperties(prefix = "emsafe.mqtt")
@Getter
@Setter
public class MqttProperties {
    private boolean enabled = false;
    private String host = "localhost";
    private int port = 1883;
    private boolean tls = false;
    private String username;
    private String password;
    private String clientId = "emsafe-backend";
    /** Espera del PUBACK al publicar con QoS 1. */
    private long publishTimeoutMs = 5000;
    /** Tope de la espera entre reintentos de conexión. */
    private long maxReconnectDelayMs = 30000;
    /** Cuánto se mantiene la suscripción no compartida que lee los retained al conectar. */
    private long retainedSyncMs = 5000;

    public String serverUri() {
        return (tls ? "ssl://" : "tcp://") + host + ":" + port;
    }
}
