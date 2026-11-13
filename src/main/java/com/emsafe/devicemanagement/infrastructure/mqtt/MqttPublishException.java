package com.emsafe.devicemanagement.infrastructure.mqtt;

/** El broker no confirmó la publicación; el mensaje dice por qué. */
public class MqttPublishException extends RuntimeException {
    public MqttPublishException(String reason) {
        super(reason);
    }

    public MqttPublishException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
