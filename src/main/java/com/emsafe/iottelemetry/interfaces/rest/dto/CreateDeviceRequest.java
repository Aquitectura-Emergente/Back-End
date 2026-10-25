package com.emsafe.iottelemetry.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateDeviceRequest(
        @NotBlank String name,
        @NotBlank String type,
        String location,
        String status,
        String serialNumber,
        String installDate,
        Long clientId
) {}
