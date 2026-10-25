package com.emsafe.radiationmonitoring.interfaces.rest.dto;

public record RadiationPointDto(
        Long id,
        Double latitude,
        Double longitude,
        String location,
        String sensorId,
        Double value,
        String level,           // edge-computed: "safe" | "caution" | "danger"
        String readingDate
) {}
