package com.emsafe.devicemanagement.infrastructure.mqtt;

import com.emsafe.devicemanagement.application.ports.PlugCommandPublisher;
import com.emsafe.devicemanagement.domain.model.PlugCommand;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.MqttContract;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Publica la orden en {@code iot/{plugId}/command}, QoS 1 y sin retain. */
@Component
@RequiredArgsConstructor
public class MqttPlugCommandPublisher implements PlugCommandPublisher {
    private final MqttGateway mqtt;

    @Override
    public boolean publish(PlugCommand command) {
        return mqtt.publish(MqttContract.commandTopic(command.getPlugSerial()),
                MqttContract.write(MqttContract.commandMessage(command)), MqttContract.QOS, false);
    }
}
