package com.emsafe.devicemanagement.infrastructure.mqtt.contract;

/** El mensaje no cumple el contrato MQTT; se descarta con un registro en el log. */
public class ContractViolation extends RuntimeException {
    public ContractViolation(String message) {
        super(message);
    }
}
