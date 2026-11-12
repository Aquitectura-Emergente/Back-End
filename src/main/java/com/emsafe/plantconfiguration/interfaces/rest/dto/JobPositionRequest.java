package com.emsafe.plantconfiguration.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta o edición de un puesto de trabajo dentro de una zona. */
public record JobPositionRequest(
        @NotNull(message = "plantZoneId is required")
        Long plantZoneId,

        @NotBlank(message = "Job position name is required")
        @Size(max = 150, message = "Job position name cannot exceed 150 characters")
        String name,

        @Size(max = 400, message = "Description cannot exceed 400 characters")
        String description
) {
}
