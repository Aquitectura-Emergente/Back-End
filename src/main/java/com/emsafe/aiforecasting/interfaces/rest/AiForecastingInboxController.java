package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.application.ForecastingAccessPolicy;
import com.emsafe.aiforecasting.application.ShiftUpdatedHandler;
import com.emsafe.aiforecasting.application.TriggerAssessmentHandler;
import com.emsafe.aiforecasting.application.UpsertExposureSnapshotHandler;
import com.emsafe.aiforecasting.interfaces.rest.dto.ForecastingRequests;
import com.emsafe.aiforecasting.interfaces.rest.dto.ForecastingResources.InboxResult;
import com.emsafe.shared.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbox REST de eventos entrantes. Idempotente por eventId: un evento repetido responde 200 con
 * {@code duplicate=true} y no tiene efectos.
 */
@RestController
@RequestMapping("/api/v1/ai-forecasting/inbox")
@Tag(name = "AI Forecasting - Inbox", description = "Eventos entrantes (solo ADMIN). Idempotentes por eventId.")
public class AiForecastingInboxController {

    private final UpsertExposureSnapshotHandler upsertSnapshot;
    private final TriggerAssessmentHandler trigger;
    private final ShiftUpdatedHandler shiftUpdated;
    private final ForecastingAccessPolicy access;

    public AiForecastingInboxController(UpsertExposureSnapshotHandler upsertSnapshot,
                                        TriggerAssessmentHandler trigger, ShiftUpdatedHandler shiftUpdated,
                                        ForecastingAccessPolicy access) {
        this.upsertSnapshot = upsertSnapshot;
        this.trigger = trigger;
        this.shiftUpdated = shiftUpdated;
        this.access = access;
    }

    @PostMapping("/exposure-snapshot")
    @Operation(summary = "Alta o actualización del snapshot de una sesión",
            description = "Mantiene el modelo de lectura local. Una captura más antigua que la guardada se ignora; "
                    + "zona, puesto y fin de turno omitidos se conservan.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Procesado o duplicado"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Formato inválido"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Solo ADMIN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "La sesión es de otro operario u organización"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Regla de dominio inválida")})
    public ApiResponse<InboxResult> exposureSnapshot(Authentication auth,
                                                     @Valid @RequestBody ForecastingRequests.ExposureSnapshot body) {
        access.requireAdministrator(access.actor(auth));
        return ApiResponse.ok(ForecastingAssembler.toResource(
                upsertSnapshot.handle(ForecastingAssembler.toCommand(body))));
    }

    @PostMapping("/exposure-warning-raised")
    @Operation(summary = "Dispara una evaluación preventiva",
            description = "Proyecta (LINEAR_RATE_V1), prioriza, recomienda y redacta en una sola transacción, y "
                    + "escribe RotationSuggested/PredictiveRiskDetected en el outbox si la criticidad es HIGH o "
                    + "CRITICAL. Supersede la evaluación activa previa de la sesión. Requiere un snapshot previo.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Evaluado o duplicado"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Formato inválido"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Solo ADMIN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Sin snapshot de la sesión o sesión ajena"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Método/unidad no soportados o proyección inválida")})
    public ApiResponse<InboxResult> exposureWarningRaised(
            Authentication auth, @Valid @RequestBody ForecastingRequests.ExposureWarningRaised body) {
        access.requireAdministrator(access.actor(auth));
        return ApiResponse.ok(ForecastingAssembler.toResource(trigger.handle(ForecastingAssembler.toCommand(body))));
    }

    @PostMapping("/shift-updated")
    @Operation(summary = "Cambia zona/puesto/fin de turno y recalcula la evaluación activa",
            description = "Si la sesión tiene una evaluación activa, se supersede y se crea una nueva con el mismo "
                    + "limitVersionId.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Procesado o duplicado"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Formato inválido"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Solo ADMIN"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Sin snapshot de la sesión o sesión ajena")})
    public ApiResponse<InboxResult> shiftUpdated(Authentication auth,
                                                 @Valid @RequestBody ForecastingRequests.ShiftUpdated body) {
        access.requireAdministrator(access.actor(auth));
        return ApiResponse.ok(ForecastingAssembler.toResource(
                shiftUpdated.handle(ForecastingAssembler.toCommand(body))));
    }
}
