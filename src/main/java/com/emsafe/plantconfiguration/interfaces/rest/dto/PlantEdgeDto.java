package com.emsafe.plantconfiguration.interfaces.rest.dto;

import java.time.LocalDateTime;

public record PlantEdgeDto(
        Long id,
        Long plantZoneId,
        String plantZoneName,
        String serialNumber,
        String status,
        LocalDateTime createdAt
) {
}
