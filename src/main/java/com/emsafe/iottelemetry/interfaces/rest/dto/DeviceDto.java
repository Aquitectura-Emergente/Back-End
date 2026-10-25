package com.emsafe.iottelemetry.interfaces.rest.dto;

import com.emsafe.iottelemetry.domain.model.Device;

public record DeviceDto(
        Long id,
        String name,
        String type,
        String location,
        String status,
        String serialNumber,
        String installDate,
        String createdAt,
        Long clientId,
        String clientName
) {
    public static DeviceDto from(Device d) {
        return new DeviceDto(
                d.getId(),
                d.getName(),
                d.getType(),
                d.getLocation(),
                d.getStatus(),
                d.getSerialNumber(),
                d.getInstallDate() != null ? d.getInstallDate().toString() : null,
                d.getCreatedAt() != null ? d.getCreatedAt().toString() : null,
                d.getClient() != null ? d.getClient().getId() : null,
                d.getClient() != null ? d.getClient().getName() : null
        );
    }
}
