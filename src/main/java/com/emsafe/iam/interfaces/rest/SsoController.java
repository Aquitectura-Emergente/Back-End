package com.emsafe.iam.interfaces.rest;

import com.emsafe.iam.application.SsoService;
import com.emsafe.iam.interfaces.rest.dto.UserDto;
import com.emsafe.shared.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
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

    /** Un operario concreto; 403 si es de otra organización. */
    @GetMapping("/workers/{id}")
    public ResponseEntity<ApiResponse<UserDto>> worker(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(ApiResponse.ok(ssoService.workerOfMyOrganization(auth.getName(), id)));
    }
}
