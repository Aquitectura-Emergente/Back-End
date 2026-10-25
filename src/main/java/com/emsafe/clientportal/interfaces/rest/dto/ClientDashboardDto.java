package com.emsafe.clientportal.interfaces.rest.dto;

import java.util.List;

/**
 * Aggregated overview for the client's mobile dashboard.
 */
public record ClientDashboardDto(
        int deviceCount,
        int activeDeviceCount,
        double currentAverage,
        double maxValue,
        String level,
        double safetyThreshold,
        int alertCount,
        List<ClientDeviceDto> devices,
        List<ClientReadingDto> latestReadings
) {}
