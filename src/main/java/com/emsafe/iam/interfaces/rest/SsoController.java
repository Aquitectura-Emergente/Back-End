package com.emsafe.iam.interfaces.rest;

import com.emsafe.iam.application.SsoService;
import com.emsafe.iam.interfaces.rest.dto.CreateWorkerRequest;
import com.emsafe.iam.interfaces.rest.dto.UpdateWorkerRequest;
import com.emsafe.iam.interfaces.rest.dto.UserDto;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Panel del Responsable de SSO (acceso restringido a SSO_RESPONSIBLE en SecurityConfig). */
@RestController
@RequestMapping("/api/sso")
@RequiredArgsConstructor
public class SsoController {

    private final SsoService ssoService;

    /** Operarios de la organización del responsable autenticado. */
    @GetMapping("/workers")
    public ResponseEntity<ApiResponse<List<UserDto>>> workers(Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(ssoService.workersOfMyOrganization(auth.getName())));
    }

    /** Alta de un operario en la organización del responsable. */
    @PostMapping("/workers")
    public ResponseEntity<ApiResponse<UserDto>> createWorker(@Valid @RequestBody CreateWorkerRequest req,
                                                             Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Worker created", ssoService.createWorker(auth.getName(), req)));
    }

    /** Edita, activa o desactiva un operario suyo; 403 si es de otra organización. */
    @PutMapping("/workers/{id}")
    public ResponseEntity<ApiResponse<UserDto>> updateWorker(@PathVariable Long id,
                                                             @Valid @RequestBody UpdateWorkerRequest req,
                                                             Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(ssoService.updateWorker(auth.getName(), id, req)));
    }

    /** Un operario concreto; 403 si es de otra organización. */
    @GetMapping("/workers/{id}")
    public ResponseEntity<ApiResponse<UserDto>> worker(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(ssoService.workerOfMyOrganization(auth.getName(), id)));
    }
}
