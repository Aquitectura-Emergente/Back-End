package com.emsafe.iottelemetry.interfaces.rest;

import com.emsafe.iottelemetry.interfaces.rest.dto.CreateDeviceRequest;
import com.emsafe.iottelemetry.interfaces.rest.dto.DeviceDto;
import com.emsafe.iottelemetry.application.DeviceService;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeviceDto>>> getAll(
            @RequestParam(required = false) Long clientId) {
        return ResponseEntity.ok(ApiResponse.ok(
                clientId != null ? deviceService.getByClient(clientId) : deviceService.getAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(deviceService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DeviceDto>> create(@Valid @RequestBody CreateDeviceRequest req) {
        DeviceDto created = deviceService.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Device created", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DeviceDto>> update(
            @PathVariable Long id,
            @RequestBody CreateDeviceRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(deviceService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        deviceService.delete(id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.ok("Device deleted", null));
    }
}
