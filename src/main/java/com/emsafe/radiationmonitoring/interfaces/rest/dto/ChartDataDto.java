package com.emsafe.radiationmonitoring.interfaces.rest.dto;

import java.util.List;

public record ChartDataDto(
        SystemActivity systemActivity,
        RadiationTrends radiationTrends,
        Regional regional
) {
    public record SystemActivity(List<Integer> series, List<String> categories) {}
    public record RadiationTrends(List<Double> series) {}
    public record Regional(List<Long> series, List<String> categories) {}
}
