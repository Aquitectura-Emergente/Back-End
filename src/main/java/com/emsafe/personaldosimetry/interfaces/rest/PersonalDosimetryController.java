package com.emsafe.personaldosimetry.interfaces.rest;

import com.emsafe.personaldosimetry.application.*;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.*;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryResources.*;
import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.shared.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/personal-dosimetry")
@RequiredArgsConstructor
public class PersonalDosimetryController {
    private final PersonalDosimetryService service;
    private final DosimetryAccessPolicy access;
    private final DosimetryAuditService audit;

    @PostMapping("/memberships")
    public ResponseEntity<ApiResponse<Membership>> grant(Authentication auth, @Valid @RequestBody GrantMembership body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.grantMembership(actor(auth), body)));
    }

    @PostMapping("/organizations/{organizationId}/limits")
    public ResponseEntity<ApiResponse<Limit>> configureLimit(Authentication auth, @PathVariable Long organizationId,
                                                            @Valid @RequestBody ConfigureLimit body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.configureLimit(actor(auth), organizationId, body)));
    }

    @GetMapping("/organizations/{organizationId}/limits")
    public ApiResponse<PageResponse<Limit>> limits(Authentication auth, @PathVariable Long organizationId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listLimits(actor(auth), organizationId, page, size));
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<Session>> open(Authentication auth, @Valid @RequestBody OpenSession body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.openSession(actor(auth), body)));
    }

    @GetMapping("/sessions/{id}")
    public ApiResponse<Session> session(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(service.getSession(actor(auth), id));
    }

    @PostMapping("/sessions/{id}/readings")
    public ApiResponse<Ingestion> ingest(Authentication auth, @PathVariable String id, @Valid @RequestBody IngestReading body) {
        return ApiResponse.ok(service.ingest(actor(auth), id, body));
    }

    @GetMapping("/sessions/{id}/readings")
    public ApiResponse<PageResponse<Reading>> readings(Authentication auth, @PathVariable String id,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.listReadings(actor(auth), id, page, size));
    }

    @PostMapping("/sessions/{id}/close")
    public ApiResponse<Session> close(Authentication auth, @PathVariable String id,
                                       @RequestBody(required = false) CloseSession body) {
        return ApiResponse.ok(service.requestClose(actor(auth), id, body));
    }

    @PostMapping("/sessions/{id}/finalize")
    public ApiResponse<Session> finalizeSession(Authentication auth, @PathVariable String id,
                                               @RequestBody(required = false) FinalizeSession body) {
        return ApiResponse.ok(service.finalizeSession(actor(auth), id, body));
    }

    @GetMapping("/workers/me/exposure")
    public ApiResponse<Session> current(Authentication auth) {
        return ApiResponse.ok(service.currentExposure(actor(auth)));
    }

    @GetMapping("/workers/{workerId}/sessions")
    public ApiResponse<PageResponse<Session>> history(Authentication auth, @PathVariable Long workerId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.history(actor(auth), workerId, page, size));
    }

    @GetMapping("/outbox")
    public ApiResponse<PageResponse<Event>> events(Authentication auth,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.pendingEvents(actor(auth), page, size));
    }

    @PostMapping("/outbox/{id}/ack")
    public ResponseEntity<Void> acknowledge(Authentication auth, @PathVariable String id) {
        service.acknowledgeEvent(actor(auth), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit")
    public ApiResponse<PageResponse<AccessAudit>> audit(Authentication auth,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(audit.list(actor(auth), page, size));
    }

    private DosimetryActor actor(Authentication auth) {
        return access.actor(auth.getName());
    }
}
