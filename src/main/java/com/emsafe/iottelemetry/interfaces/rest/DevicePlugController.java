package com.emsafe.iottelemetry.interfaces.rest;

import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.iottelemetry.interfaces.rest.dto.PlugStateDto;
import com.emsafe.iottelemetry.application.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de estado del relé (plug) para el EDGE (v1, sin JWT — igual que /api/v1/readings).
 *
 * - GET /api/v1/devices/{serialNumber}/plug → estado DESEADO del relé (lo que
 *   el usuario ordenó desde la app). El edge lo consulta en cada ciclo y se lo
 *   pasa al dispositivo para abrir/cerrar la corriente.
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DevicePlugController {

    private final TelemetryService telemetryService;

    @GetMapping("/{serialNumber}/plug")
    public ResponseEntity<ApiResponse<PlugStateDto>> desiredPlug(@PathVariable String serialNumber) {
        return ResponseEntity.ok(ApiResponse.ok(
                new PlugStateDto(telemetryService.getDesiredPlug(serialNumber))));
    }
}
