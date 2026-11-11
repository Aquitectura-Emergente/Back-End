package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.application.AckOutboxEventHandler;
import com.emsafe.aiforecasting.application.ForecastingAccessPolicy;
import com.emsafe.aiforecasting.application.OutboxQueryHandler;
import com.emsafe.aiforecasting.interfaces.rest.dto.ForecastingResources.OutboxEvent;
import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.shared.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-forecasting/outbox")
@Tag(name = "AI Forecasting - Outbox",
        description = "Eventos salientes (RotationSuggested, PredictiveRiskDetected) pendientes de publicar. Solo ADMIN.")
public class AiForecastingOutboxController {

    private final OutboxQueryHandler outbox;
    private final AckOutboxEventHandler ack;
    private final ForecastingAccessPolicy access;

    public AiForecastingOutboxController(OutboxQueryHandler outbox, AckOutboxEventHandler ack,
                                         ForecastingAccessPolicy access) {
        this.outbox = outbox;
        this.ack = ack;
        this.access = access;
    }

    @GetMapping
    @Operation(summary = "Eventos pendientes", description = "publishedAt nulo, los más antiguos primero.")
    public ApiResponse<PageResponse<OutboxEvent>> pending(
            Authentication auth,
            @Parameter(description = "Desde 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Entre 1 y 200") @RequestParam(defaultValue = "50") int size) {
        access.requireAdministrator(access.actor(auth));
        return ApiResponse.ok(ForecastingAssembler.toPage(outbox.pending(page, size), ForecastingAssembler::toResource));
    }

    @PostMapping("/{eventId}/ack")
    @Operation(summary = "Marca un evento como publicado",
            description = "Idempotente: repetir el ack conserva la fecha original. 404 si el evento no existe.")
    public ApiResponse<OutboxEvent> acknowledge(Authentication auth, @PathVariable String eventId) {
        access.requireAdministrator(access.actor(auth));
        return ApiResponse.ok(ForecastingAssembler.toResource(ack.handle(eventId)));
    }
}
