package com.emsafe.iottelemetry.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * A sensor "discovered" by the edge but not yet assigned to a client
 * (Device.status = "unregistered"). Shown in the technician's installation
 * panel so the sensor can be claimed by its live reading (wiggle test) rather
 * than by typing the edge-generated serial.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoverableDeviceDto(
        Long deviceId,
        String serialNumber,
        String name,
        String type,
        @JsonProperty("field_uT") Double fieldUT,
        String level,
        LocalDateTime lastSeen,
        long readingCount
) {}
