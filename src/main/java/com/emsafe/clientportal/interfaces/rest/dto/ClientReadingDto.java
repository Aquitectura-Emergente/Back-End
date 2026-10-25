package com.emsafe.clientportal.interfaces.rest.dto;

/**
 * A single radiation reading belonging to one of the client's devices.
 */
public record ClientReadingDto(
        Long id,
        Double value,
        String level,
        String readingDate,
        String recordedAt,   // precise ISO timestamp of the measurement (for time-of-day display)
        Long deviceId,
        String deviceName
) {}
