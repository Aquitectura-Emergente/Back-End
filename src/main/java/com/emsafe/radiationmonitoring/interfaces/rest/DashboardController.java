package com.emsafe.radiationmonitoring.interfaces.rest;

import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.ChartDataDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.ClientRadiationDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.LatestWorkOrderDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.RadiationPointDto;
import com.emsafe.radiationmonitoring.interfaces.rest.dto.StatsDto;
import com.emsafe.radiationmonitoring.application.DashboardService;
import com.emsafe.shared.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<StatsDto>> getStats() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getStats()));
    }

    @GetMapping("/alerts")
    public ResponseEntity<ApiResponse<List<AlertDto>>> getAlerts() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getAlerts()));
    }

    @GetMapping("/latest-work-orders")
    public ResponseEntity<ApiResponse<List<LatestWorkOrderDto>>> getLatestWorkOrders() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getLatestWorkOrders()));
    }

    @GetMapping("/chart-data")
    public ResponseEntity<ApiResponse<ChartDataDto>> getChartData() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getChartData()));
    }

    @GetMapping("/radiation-map")
    public ResponseEntity<ApiResponse<List<RadiationPointDto>>> getRadiationMap() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getRadiationMap()));
    }

    @GetMapping("/radiation-map/by-client")
    public ResponseEntity<ApiResponse<List<ClientRadiationDto>>> getRadiationMapByClient() {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getRadiationMapByClient()));
    }
}
