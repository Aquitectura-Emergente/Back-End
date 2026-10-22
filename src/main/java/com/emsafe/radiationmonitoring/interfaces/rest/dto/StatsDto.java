package com.emsafe.radiationmonitoring.interfaces.rest.dto;

public record StatsDto(
        long totalSensors,
        String sensorsDelta,
        long activeClients,
        String clientsDelta,
        long pendingTasks,
        String tasksStatus,
        long criticalAlerts,
        String alertsStatus,
        double avgCompletionHours,
        double currentRadiationAvg,
        double safetyThreshold
) {}
