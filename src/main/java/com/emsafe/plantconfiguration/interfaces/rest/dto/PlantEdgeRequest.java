package com.emsafe.plantconfiguration.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Declaración del edge que sirve una zona. Uno por zona. */
public record PlantEdgeRequest(
        @NotNull(message = "plantZoneId is required")
        Long plantZoneId,

        @NotBlank(message = "Edge serial number is required")
        @Size(max = 100, message = "Edge serial number cannot exceed 100 characters")
        String serialNumber
) {
}
