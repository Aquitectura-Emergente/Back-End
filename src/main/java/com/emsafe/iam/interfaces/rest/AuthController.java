package com.emsafe.iam.interfaces.rest;

import com.emsafe.iam.interfaces.rest.dto.LoginRequest;
import com.emsafe.iam.interfaces.rest.dto.LoginResponse;
import com.emsafe.iam.interfaces.rest.dto.RefreshResponse;
import com.emsafe.iam.interfaces.rest.dto.RegisterRequest;
import com.emsafe.iam.application.AuthService;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;



@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest req) {
        LoginResponse response = authService.login(req);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
            @RequestHeader("Authorization") String authHeader) {
        return ResponseEntity.ok(ApiResponse.ok(authService.refresh(authHeader)));
    }

    /** Public sign-up (mobile app) — account stays pending until an admin activates it. */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest req) {
        authService.register(req);
        return ResponseEntity.ok(ApiResponse.ok(
                "Account created. An administrator will review and activate it shortly.", null));
    }
}
