package com.emsafe.plantconfiguration.interfaces.rest;

import com.emsafe.plantconfiguration.application.JobPositionService;
import com.emsafe.plantconfiguration.application.PlantConfigurationAccessPolicy;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionDto;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Puestos de trabajo de una planta, agrupados por zona. */
@RestController
@RequestMapping("/api/v1/plant-config/job-positions")
@RequiredArgsConstructor
public class JobPositionController {

    private final JobPositionService positionService;
    private final PlantConfigurationAccessPolicy accessPolicy;

    @GetMapping
    public ResponseEntity<ApiResponse<List<JobPositionDto>>> list(
            Authentication auth,
            @RequestParam(required = false) Long plantZoneId) {
        Long org = accessPolicy.assertCanRead(auth);
        return ResponseEntity.ok(ApiResponse.ok(positionService.listFor(org, plantZoneId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<JobPositionDto>> getById(
            Authentication auth, @PathVariable Long id) {
        Long org = accessPolicy.assertCanRead(auth);
        return ResponseEntity.ok(ApiResponse.ok(positionService.getFor(org, id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<JobPositionDto>> create(
            Authentication auth, @Valid @RequestBody JobPositionRequest req) {
        Long org = accessPolicy.assertCanConfigure(auth);
        JobPositionDto created = positionService.create(org, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Job position created", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<JobPositionDto>> update(
            Authentication auth, @PathVariable Long id,
            @Valid @RequestBody JobPositionRequest req) {
        Long org = accessPolicy.assertCanConfigure(auth);
        return ResponseEntity.ok(ApiResponse.ok(positionService.update(org, id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            Authentication auth, @PathVariable Long id) {
        Long org = accessPolicy.assertCanConfigure(auth);
        positionService.delete(org, id);
        return ResponseEntity.ok(ApiResponse.ok("Job position deleted", null));
    }
}
