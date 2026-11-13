package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

/** Identificador del dispositivo: el {@code deviceId} del contrato MQTT y su nombre BLE. */
public record DeviceSerial(String value) {
    public DeviceSerial {
        value = value == null ? null : value.trim();
        if (value == null || value.isEmpty()) throw new BadRequestException("Device serial is required");
        if (value.length() > 100) throw new BadRequestException("Device serial cannot exceed 100 characters");
    }

    public static DeviceSerial of(String value) {
        return new DeviceSerial(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
