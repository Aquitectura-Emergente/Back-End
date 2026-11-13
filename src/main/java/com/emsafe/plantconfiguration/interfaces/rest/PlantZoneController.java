package com.emsafe.plantconfiguration.interfaces.rest;

import com.emsafe.plantconfiguration.application.PlantConfigurationAccessPolicy;
import com.emsafe.plantconfiguration.application.PlantZoneService;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantZoneDto;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantZoneRequest;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Zonas de planta: el catálogo que configura el Responsable de SSO.
 *
 * <p>La organización nunca llega por parámetro — se deriva del usuario
 * autenticado en {@link PlantConfigurationAccessPolicy}. Si viajara en la URL,
 * un SSO podría leer o escribir en otra planta cambiando un número.
 */
@RestController
@RequestMapping("/api/v1/plant-config/zones")
@RequiredArgsConstructor
public class PlantZoneController {

    private final PlantZoneService zoneService;
    private final PlantConfigurationAccessPolicy accessPolicy;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PlantZoneDto>>> list(Authentication auth) {
        Long org = accessPolicy.assertCanRead(auth);
        return ResponseEntity.ok(ApiResponse.ok(zoneService.listFor(org)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PlantZoneDto>> getById(
            Authentication auth, @PathVariable Long id) {
        Long org = accessPolicy.assertCanRead(auth);
        return ResponseEntity.ok(ApiResponse.ok(zoneService.getFor(org, id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PlantZoneDto>> create(
            Authentication auth, @Valid @RequestBody PlantZoneRequest req) {
        Long org = accessPolicy.assertCanConfigure(auth);
        PlantZoneDto created = zoneService.create(org, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Plant zone created", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PlantZoneDto>> update(
            Authentication auth, @PathVariable Long id,
            @Valid @RequestBody PlantZoneRequest req) {
        Long org = accessPolicy.assertCanConfigure(auth);
        return ResponseEntity.ok(ApiResponse.ok(zoneService.update(org, id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            Authentication auth, @PathVariable Long id) {
        Long org = accessPolicy.assertCanConfigure(auth);
        zoneService.delete(org, id);
        return ResponseEntity.ok(ApiResponse.ok("Plant zone deleted", null));
    }
}
