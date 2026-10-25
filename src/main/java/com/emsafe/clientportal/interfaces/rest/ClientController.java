package com.emsafe.clientportal.interfaces.rest;

import com.emsafe.iam.infrastructure.security.JwtUtil;
import com.emsafe.clientportal.interfaces.rest.dto.*;
import com.emsafe.clientportal.application.AssistantService;
import com.emsafe.clientportal.application.ClientService;
import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.iam.interfaces.rest.dto.ChangePasswordRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints for the EMSafe mobile app (clients). Every request is scoped to the
 * authenticated client via the userId carried in the JWT — same pattern as the
 * technician portal (/api/tech/**).
 */
@RestController
@RequestMapping("/api/client")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService clientService;
    private final AssistantService assistantService;
    private final JwtUtil jwtUtil;

    // ─── Profile ────────────────────────────────────────────────────────────

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<ClientProfileDto>> getProfile(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(clientService.getProfile(extractUserId(request))));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<ClientProfileDto>> updateProfile(
            HttpServletRequest request,
            @RequestBody UpdateClientProfileRequest req) {
        return ResponseEntity.ok(ApiResponse.ok("Profile updated",
                clientService.updateProfile(extractUserId(request), req)));
    }

    @PatchMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            HttpServletRequest request,
            @Valid @RequestBody ChangePasswordRequest req) {
        clientService.changePassword(extractUserId(request), req);
        return ResponseEntity.ok(ApiResponse.ok("Password updated", null));
    }

    /** Self-service deletion of the authenticated client's account (password-confirmed). */
    @PostMapping("/account/delete")
    public ResponseEntity<ApiResponse<Void>> deleteAccount(
            HttpServletRequest request,
            @RequestBody DeleteAccountRequest req) {
        clientService.deleteAccount(extractUserId(request), req.password());
        return ResponseEntity.ok(ApiResponse.ok("Account deleted", null));
    }

    // ─── Devices ──────────────────────────────────────────────────────────────

    @GetMapping("/devices")
    public ResponseEntity<ApiResponse<List<ClientDeviceDto>>> getDevices(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(clientService.getDevices(extractUserId(request))));
    }

    @GetMapping("/devices/{id}")
    public ResponseEntity<ApiResponse<ClientDeviceDto>> getDevice(
            HttpServletRequest request, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(clientService.getDeviceDetail(extractUserId(request), id)));
    }

    @GetMapping("/devices/{id}/readings")
    public ResponseEntity<ApiResponse<List<ClientReadingDto>>> getDeviceReadings(
            HttpServletRequest request, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(clientService.getDeviceReadings(extractUserId(request), id)));
    }

    /** El cliente ordena abrir/cerrar el relé de SU dispositivo (camino de vuelta al edge). */
    @PatchMapping("/devices/{id}/plug")
    public ResponseEntity<ApiResponse<ClientDeviceDto>> setPlug(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestBody SetPlugRequest req) {
        return ResponseEntity.ok(ApiResponse.ok("Plug state updated",
                clientService.setDesiredPlug(extractUserId(request), id, req.plug())));
    }

    // ─── Readings / Dashboard / Alerts ────────────────────────────────────────

    @GetMapping("/readings")
    public ResponseEntity<ApiResponse<List<ClientReadingDto>>> getReadings(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(clientService.getReadings(extractUserId(request))));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<ClientDashboardDto>> getDashboard(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(clientService.getDashboard(extractUserId(request))));
    }

    @GetMapping("/alerts")
    public ResponseEntity<ApiResponse<List<ClientAlertDto>>> getAlerts(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(clientService.getAlerts(extractUserId(request))));
    }

    /** Aggregated radiation report — ?period=month (last 30 days) | year (last 12 months). */
    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<ClientReportDto>> getReport(
            HttpServletRequest request,
            @RequestParam(defaultValue = "month") String period) {
        return ResponseEntity.ok(ApiResponse.ok(
                clientService.getReport(extractUserId(request), period)));
    }

    /** "Astra" assistant (US10) — proxied to Gemini; the API key never leaves the backend. */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatReplyDto>> chat(
            HttpServletRequest request,
            @Valid @RequestBody ChatRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                assistantService.chat(extractUserId(request), req)));
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private Long extractUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return jwtUtil.extractUserId(header.substring(7));
        }
        return null;
    }
}
