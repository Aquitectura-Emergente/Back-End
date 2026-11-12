package com.emsafe.plantconfiguration.interfaces.rest;

import com.emsafe.plantconfiguration.application.PlantConfigurationAccessPolicy;
import com.emsafe.plantconfiguration.application.PlantEdgeService;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantEdgeDto;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantEdgeRequest;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * El edge que sirve cada zona. Uno por zona.
 *
 * <p>Activar y desactivar van por {@code PATCH} sobre un subrecurso y no por
 * {@code PUT} del objeto entero, porque son transiciones de estado con
 * significado propio y no una edición libre del campo.
 */
@RestController
@RequestMapping("/api/v1/plant-config/edges")
@RequiredArgsConstructor
public class PlantEdgeController {

    private final PlantEdgeService edgeService;
    private final PlantConfigurationAccessPolicy accessPolicy;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PlantEdgeDto>>> list(Authentication auth) {
        Long org = accessPolicy.assertCanRead(auth);
        return ResponseEntity.ok(ApiResponse.ok(edgeService.listFor(org)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PlantEdgeDto>> declare(
            Authentication auth, @Valid @RequestBody PlantEdgeRequest req) {
        Long org = accessPolicy.assertCanConfigure(auth);
        PlantEdgeDto created = edgeService.declare(org, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Edge declared", created));
    }

    @PatchMapping("/{id}/serial")
    public ResponseEntity<ApiResponse<PlantEdgeDto>> updateSerial(
            Authentication auth, @PathVariable Long id,
            @RequestParam String serialNumber) {
        Long org = accessPolicy.assertCanConfigure(auth);
        return ResponseEntity.ok(
                ApiResponse.ok(edgeService.updateSerial(org, id, serialNumber)));
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<PlantEdgeDto>> activate(
            Authentication auth, @PathVariable Long id) {
        Long org = accessPolicy.assertCanConfigure(auth);
        return ResponseEntity.ok(ApiResponse.ok(edgeService.setActive(org, id, true)));
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<ApiResponse<PlantEdgeDto>> deactivate(
            Authentication auth, @PathVariable Long id) {
        Long org = accessPolicy.assertCanConfigure(auth);
        return ResponseEntity.ok(ApiResponse.ok(edgeService.setActive(org, id, false)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            Authentication auth, @PathVariable Long id) {
        Long org = accessPolicy.assertCanConfigure(auth);
        edgeService.delete(org, id);
        return ResponseEntity.ok(ApiResponse.ok("Edge removed", null));
    }
}
