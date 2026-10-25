package com.emsafe.iottelemetry.interfaces.rest;

import com.emsafe.iottelemetry.interfaces.rest.dto.DiscoverableDeviceDto;
import com.emsafe.iottelemetry.application.DeviceService;
import com.emsafe.shared.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Technician device endpoints (TECHNICIAN + ADMIN via {@code /api/tech/**}).
 */
@RestController
@RequestMapping("/api/tech/devices")
@RequiredArgsConstructor
public class TechDeviceController {

    private final DeviceService deviceService;

    /** Sensors discovered by the edge, not yet assigned, available to claim during an installation. */
    @GetMapping("/discoverable")
    public ResponseEntity<ApiResponse<List<DiscoverableDeviceDto>>> getDiscoverable() {
        return ResponseEntity.ok(ApiResponse.ok(deviceService.getDiscoverable()));
    }
}
