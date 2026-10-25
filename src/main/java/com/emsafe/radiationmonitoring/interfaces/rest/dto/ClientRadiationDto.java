package com.emsafe.radiationmonitoring.interfaces.rest.dto;

import java.util.List;

public record ClientRadiationDto(
        Long clientId,
        String clientName,
        Double latitude,
        Double longitude,
        String location,
        Double maxValue,
        String level,           // "safe" | "caution" | "danger"
        List<DeviceReadingDto> devices
) {
    public record DeviceReadingDto(
            Long deviceId,
            String deviceName,
            String deviceType,
            String serialNumber,
            String deviceLocation,
            String deviceStatus,
            Double latestValue,
            String level,           // edge-computed: "safe" | "caution" | "danger"
            String readingDate
    ) {}
}
