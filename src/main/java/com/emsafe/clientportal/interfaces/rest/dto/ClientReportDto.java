package com.emsafe.clientportal.interfaces.rest.dto;

import java.util.List;

/**
 * Aggregated radiation report for the mobile app (US19/US20/US22 + TS07).
 *
 * period = "month" → last 30 days bucketed per day.
 * period = "year"  → last 12 months bucketed per month.
 */
public record ClientReportDto(
        String period,
        double average,
        double peak,
        int totalReadings,
        int totalAlerts,
        List<Bucket> buckets
) {
    /** One aggregation bucket (a day or a month), oldest first. */
    public record Bucket(
            String label,
            double average,
            double peak,
            int readings,
            int alerts
    ) {}
}
