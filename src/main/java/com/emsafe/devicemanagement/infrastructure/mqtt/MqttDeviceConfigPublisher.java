package com.emsafe.devicemanagement.infrastructure.mqtt;

import com.emsafe.devicemanagement.application.ports.DeviceConfigPublisher;
import com.emsafe.devicemanagement.application.ports.PublicationFailedException;
import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.MqttContract;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Publica la configuración retained en {@code iot/{deviceId}/config}: el Edge la recibe al conectar. */
@Component
@RequiredArgsConstructor
public class MqttDeviceConfigPublisher implements DeviceConfigPublisher {
    private static final Logger log = LoggerFactory.getLogger(MqttDeviceConfigPublisher.class);
    private final MqttGateway mqtt;

    @Override
    public void publish(DeviceConfigChanged config) {
        String topic = MqttContract.configTopic(config.deviceId());
        try {
            mqtt.publish(topic, MqttContract.write(MqttContract.configMessage(config)), MqttContract.QOS, true);
        } catch (MqttPublishException e) {
            throw new PublicationFailedException(topic + ": " + e.getMessage(), e);
        }
        log.info("Published config v{} for {}", config.configVersion(), config.deviceId());
    }
}
