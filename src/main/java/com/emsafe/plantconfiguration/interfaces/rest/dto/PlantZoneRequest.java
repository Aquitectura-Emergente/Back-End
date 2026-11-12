package com.emsafe.plantconfiguration.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta o edición de una zona. La organización NO viaja en el cuerpo: se toma de
 * quien llama, para que un SSO no pueda crear zonas en otra planta.
 */
public record PlantZoneRequest(
        @NotBlank(message = "Plant zone name is required")
        @Size(max = 150, message = "Plant zone name cannot exceed 150 characters")
        String name,

        @Size(max = 400, message = "Description cannot exceed 400 characters")
        String description
) {
}
