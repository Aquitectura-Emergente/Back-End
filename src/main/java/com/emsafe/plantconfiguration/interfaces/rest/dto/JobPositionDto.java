package com.emsafe.plantconfiguration.interfaces.rest.dto;

import java.time.LocalDateTime;

public record JobPositionDto(
        Long id,
        Long plantZoneId,
        String plantZoneName,
        String name,
        String description,
        LocalDateTime createdAt
) {
}
