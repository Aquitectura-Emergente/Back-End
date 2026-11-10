package com.emsafe.occupationalhealth.interfaces.rest;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.occupationalhealth.application.*;
import com.emsafe.occupationalhealth.domain.model.MitigationAction;
import com.emsafe.occupationalhealth.domain.model.MitigationState;
import com.emsafe.occupationalhealth.interfaces.rest.dto.ApproveMitigationActionRequest;
import com.emsafe.occupationalhealth.interfaces.rest.dto.MitigationActionResource;
import com.emsafe.occupationalhealth.interfaces.rest.dto.RegisterRelocationRequest;
import com.emsafe.occupationalhealth.interfaces.rest.dto.RejectMitigationActionRequest;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * REST de las medidas de mitigación (informe §5.6.2).
 *
 * Aprobar y rechazar son POST sobre subrecursos y no un PATCH del estado,
 * porque cada uno ejecuta una transición de dominio con validaciones y efectos
 * —revalidar márgenes, reasignar turnos, publicar eventos— y no una edición
 * libre del campo state.
 *
 * El prefijo cae bajo /api/v1/**, que es público en SecurityConfig para que el
 * edge pueda ingestar sin JWT. Por eso hay una regla explícita para esta ruta
 * declarada ANTES de ese permitAll; sin ella, aprobar una medida sería un
 * endpoint abierto.
 */
@RestController
@RequestMapping("/api/v1/occupational-health/mitigation-actions")
@RequiredArgsConstructor
public class MitigationActionController {

    private final MitigationQueryHandler queries;
    private final ApproveMitigationActionCommandHandler approveHandler;
    private final RejectMitigationActionCommandHandler rejectHandler;
    private final RegisterRelocationCommandHandler relocationHandler;
    private final IdempotencyGuard idempotency;
    private final MitigationAccessPolicy accessPolicy;

    /**
     * La bandeja del SSO, o el historial de un operario si se pasa workerId.
     * Son el mismo recurso con distinto filtro, como en el informe.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<MitigationActionResource>>> list(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Long workerId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            Authentication auth) {
        AppUser user = accessPolicy.assertCanRead(auth);
        Long organizationId = accessPolicy.organizationOf(user);

        List<MitigationAction> actions = (workerId != null)
                ? queries.actionHistory(organizationId, workerId, from, to)
                : queries.pendingActions(organizationId, state);

        return ResponseEntity.ok(ApiResponse.ok(
                actions.stream().map(MitigationActionResource::from).toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MitigationActionResource>> getById(
            @PathVariable Long id, Authentication auth) {
        AppUser user = accessPolicy.assertCanRead(auth);
        MitigationAction action = queries.actionById(accessPolicy.organizationOf(user), id);
        return ResponseEntity.ok(ApiResponse.ok(MitigationActionResource.from(action)));
    }

    @PostMapping("/relocations")
    public ResponseEntity<ApiResponse<MitigationActionResource>> registerRelocation(
            @Valid @RequestBody RegisterRelocationRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication auth) {
        AppUser sso = accessPolicy.assertCanDecide(auth);
        Long organizationId = accessPolicy.organizationOf(sso);

        Long actionId = idempotency.runOnce(organizationId, idempotencyKey,
                "POST /mitigation-actions/relocations",
                () -> relocationHandler.handle(organizationId, sso.getId(), req.workerId(),
                        req.toPositionId(), req.toZoneId(), req.justification()).getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                "Relocation applied",
                MitigationActionResource.from(queries.actionById(organizationId, actionId))));
    }

    /**
     * Aprueba la medida. Si la revalidación la invalida, el handler la cierra
     * como NOT_VIABLE y propone reprogramar: eso no es un error del servidor
     * sino un conflicto con el estado de la planta, y se devuelve 409 con la
     * acción tal como quedó, como indica la tabla de endpoints del informe.
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<MitigationActionResource>> approve(
            @PathVariable Long id,
            @RequestBody(required = false) ApproveMitigationActionRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication auth) {
        AppUser sso = accessPolicy.assertCanDecide(auth);
        Long organizationId = accessPolicy.organizationOf(sso);

        // Con Idempotency-Key, un doble toque devuelve el resultado de la primera
        // llamada en vez del 409 que daría la transición ya ejecutada.
        idempotency.runOnce(organizationId, idempotencyKey,
                "POST /mitigation-actions/" + id + "/approve",
                () -> approveHandler.handle(organizationId, id, sso.getId(),
                        req != null ? req.reason() : null,
                        req != null ? req.toPositionId() : null,
                        req != null ? req.toZoneId() : null).getId());

        MitigationAction action = queries.actionById(organizationId, id);
        MitigationActionResource body = MitigationActionResource.from(action);
        if (action.getState() == MitigationState.NOT_VIABLE) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.ok(action.getOutcome(), body));
        }
        return ResponseEntity.ok(ApiResponse.ok("Mitigation action applied", body));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<MitigationActionResource>> reject(
            @PathVariable Long id,
            @Valid @RequestBody RejectMitigationActionRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication auth) {
        AppUser sso = accessPolicy.assertCanDecide(auth);
        Long organizationId = accessPolicy.organizationOf(sso);

        idempotency.runOnce(organizationId, idempotencyKey,
                "POST /mitigation-actions/" + id + "/reject",
                () -> rejectHandler.handle(organizationId, id, sso.getId(), req.reason()).getId());

        return ResponseEntity.ok(ApiResponse.ok("Mitigation action rejected",
                MitigationActionResource.from(queries.actionById(organizationId, id))));
    }
}
