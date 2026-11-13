package com.emsafe.devicemanagement.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code deviceType} es opcional: PERSONAL_DOSIMETER, FIXED_SENSOR (por defecto) o SMART_PLUG. */
public record CreateDeviceRequest(
        @NotBlank String name,
        @NotBlank String type,
        String location,
        String status,
        String serialNumber,
        String installDate,
        Long clientId,
        String deviceType
) {
    public CreateDeviceRequest(String name, String type, String location, String status,
                               String serialNumber, String installDate, Long clientId) {
        this(name, type, location, status, serialNumber, installDate, clientId, null);
    }
}
