package com.emsafe.devicemanagement.interfaces.rest;

import com.emsafe.devicemanagement.application.TelemetryService;
import com.emsafe.devicemanagement.interfaces.rest.dto.ReadingDto;
import com.emsafe.shared.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Consulta de lecturas, con sesión iniciada. La ingesta es por MQTT (iot/{deviceId}/telemetry). */
@RestController
@RequestMapping("/api/v1/readings")
@RequiredArgsConstructor
public class ReadingController {
    private final TelemetryService telemetryService;
    private final DeviceActorResolver actors;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ReadingDto>>> list(
            @RequestParam(required = false) String serialNumber,
            @RequestParam(defaultValue = "50") int limit,
            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(telemetryService.list(actors.resolve(auth), serialNumber, limit)));
    }

    @GetMapping("/{serialNumber}/latest")
    public ResponseEntity<ApiResponse<ReadingDto>> latest(@PathVariable String serialNumber, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(telemetryService.latest(actors.resolve(auth), serialNumber)));
    }
}
