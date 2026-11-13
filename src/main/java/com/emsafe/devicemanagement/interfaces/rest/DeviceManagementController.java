package com.emsafe.devicemanagement.interfaces.rest;

import com.emsafe.devicemanagement.application.DeviceManagementService;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.AuditEntryView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.DeviceConfigView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.DeviceStatusView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.EdgeNodeView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.TelemetryReadingView;
import com.emsafe.devicemanagement.interfaces.rest.dto.DeviceManagementRequests.ChangeAlertThreshold;
import com.emsafe.devicemanagement.interfaces.rest.dto.DeviceManagementRequests.ChangeLifecycle;
import com.emsafe.devicemanagement.interfaces.rest.dto.DeviceManagementRequests.ChangeOperationalConfig;
import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.shared.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/device-management")
@RequiredArgsConstructor
public class DeviceManagementController {
    private final DeviceManagementService service;
    private final DeviceActorResolver actors;

    @GetMapping("/devices/{deviceId}")
    public ResponseEntity<ApiResponse<DeviceStatusView>> status(@PathVariable String deviceId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(service.status(actors.resolve(auth), deviceId)));
    }

    @GetMapping("/devices/{deviceId}/config")
    public ResponseEntity<ApiResponse<DeviceConfigView>> config(@PathVariable String deviceId, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(service.config(actors.resolve(auth), deviceId)));
    }

    @PutMapping("/devices/{deviceId}/config/alert-threshold")
    public ResponseEntity<ApiResponse<DeviceConfigView>> changeThreshold(@PathVariable String deviceId,
                                                                        @Valid @RequestBody ChangeAlertThreshold req,
                                                                        Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok("Alert threshold updated",
                service.changeAlertThreshold(actors.resolve(auth), deviceId, req.alertThresholdUT())));
    }

    @PutMapping("/devices/{deviceId}/config/operational")
    public ResponseEntity<ApiResponse<DeviceConfigView>> changeOperational(@PathVariable String deviceId,
                                                                          @RequestBody ChangeOperationalConfig req,
                                                                          Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok("Configuration updated", service.changeOperationalConfig(
                actors.resolve(auth), deviceId, req.samplingIntervalMs(), req.inactivityTimeoutS())));
    }

    @PutMapping("/devices/{deviceId}/lifecycle")
    public ResponseEntity<ApiResponse<DeviceStatusView>> changeLifecycle(@PathVariable String deviceId,
                                                                        @Valid @RequestBody ChangeLifecycle req,
                                                                        Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(service.changeLifecycle(actors.resolve(auth), deviceId, req.status())));
    }

    @GetMapping("/devices/{deviceId}/audit")
    public ResponseEntity<ApiResponse<PageResponse<AuditEntryView>>> audit(@PathVariable String deviceId,
                                                                          @RequestParam(defaultValue = "0") int page,
                                                                          @RequestParam(defaultValue = "50") int size,
                                                                          Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(service.audit(actors.resolve(auth), deviceId, page, size)));
    }

    @GetMapping("/devices/{deviceId}/telemetry")
    public ResponseEntity<ApiResponse<List<TelemetryReadingView>>> telemetry(@PathVariable String deviceId,
                                                                            @RequestParam(required = false) String routing,
                                                                            @RequestParam(defaultValue = "50") int limit,
                                                                            Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(service.telemetry(actors.resolve(auth), deviceId, routing, limit)));
    }

    @GetMapping("/edges")
    public ResponseEntity<ApiResponse<List<EdgeNodeView>>> edges(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(service.edges(actors.resolve(auth))));
    }
}
