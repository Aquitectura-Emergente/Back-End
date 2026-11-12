package com.emsafe.occupationalhealth.interfaces.rest;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.occupationalhealth.application.MitigationAccessPolicy;
import com.emsafe.occupationalhealth.application.MitigationQueryHandler;
import com.emsafe.occupationalhealth.application.StartWorkerShiftCommandHandler;
import com.emsafe.occupationalhealth.domain.model.WorkerShift;
import com.emsafe.occupationalhealth.interfaces.rest.dto.StartWorkerShiftRequest;
import com.emsafe.occupationalhealth.interfaces.rest.dto.WorkerShiftResource;
import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.shared.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * REST de los turnos (informe §5.6.2).
 *
 * Las lecturas las puede hacer el propio operario sobre sus datos; abrir y
 * cerrar turno es solo del SSO.
 */
@RestController
@RequestMapping("/api/v1/occupational-health/workers/{workerId}/shifts")
@RequiredArgsConstructor
public class WorkerShiftController {

    private final MitigationQueryHandler queries;
    private final StartWorkerShiftCommandHandler shiftHandler;
    private final MitigationAccessPolicy accessPolicy;

    @GetMapping("/current")
    public ResponseEntity<ApiResponse<WorkerShiftResource>> getCurrent(
            @PathVariable Long workerId, Authentication auth) {
        AppUser user = accessPolicy.assertCanSeeWorker(auth, workerId);
        WorkerShift shift = queries
                .currentShift(accessPolicy.organizationOf(user), workerId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Active shift for worker", workerId));
        return ResponseEntity.ok(ApiResponse.ok(WorkerShiftResource.from(shift)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<WorkerShiftResource>>> getHistory(
            @PathVariable Long workerId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            Authentication auth) {
        AppUser user = accessPolicy.assertCanSeeWorker(auth, workerId);
        List<WorkerShift> shifts = queries.shiftHistory(
                accessPolicy.organizationOf(user), workerId, from, to);
        return ResponseEntity.ok(ApiResponse.ok(
                shifts.stream().map(WorkerShiftResource::from).toList()));
    }

    /** Añadido sobre el informe — ver StartWorkerShiftCommandHandler. */
    @PostMapping
    public ResponseEntity<ApiResponse<WorkerShiftResource>> start(
            @PathVariable Long workerId,
            @Valid @RequestBody StartWorkerShiftRequest req,
            Authentication auth) {
        AppUser sso = accessPolicy.assertCanDecide(auth);
        WorkerShift shift = shiftHandler.handle(
                accessPolicy.organizationOf(sso), workerId,
                req.jobPositionId(), req.plantZoneId(), req.exposureSessionId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Shift started", WorkerShiftResource.from(shift)));
    }

    @PostMapping("/current/close")
    public ResponseEntity<ApiResponse<Void>> close(
            @PathVariable Long workerId, Authentication auth) {
        AppUser sso = accessPolicy.assertCanDecide(auth);
        shiftHandler.close(accessPolicy.organizationOf(sso), workerId);
        return ResponseEntity.ok(ApiResponse.ok("Shift closed", null));
    }
}
