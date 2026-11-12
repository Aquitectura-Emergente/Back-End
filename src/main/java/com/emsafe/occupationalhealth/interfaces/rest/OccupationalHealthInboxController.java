package com.emsafe.occupationalhealth.interfaces.rest;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.inbound.*;
import com.emsafe.occupationalhealth.interfaces.rest.dto.inbox.*;
import com.emsafe.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Entrada de los hechos que publican otros contextos.
 *
 * ── AÑADIDO QUE EL INFORME NO TIENE, Y ES PROVISIONAL ─────────────────────────
 * §5.6.2 define cuatro consumers que reciben estos hechos desde Azure Service
 * Bus. Esta solución no tiene broker, y los contextos que los publican
 * —Personal Dosimetry y AI Forecasting— todavía no existen. Sin alguna forma de
 * inyectarlos, dos de las tres puertas de entrada del contexto (LIMIT_EXCEEDED y
 * AI_SUGGESTION) serían código inalcanzable: nadie podría abrir ni demostrar una
 * rotación.
 *
 * Este controller es ese sustituto. Publica el evento en proceso y los handlers
 * reaccionan exactamente como reaccionarían ante el broker, inbox incluido.
 *
 * Es ADMIN-only a propósito: no es una capacidad del negocio. Cuando exista
 * broker o los módulos publiquen en proceso, **este controller se borra** y no
 * hay que tocar ningún handler.
 */
@RestController
@RequestMapping("/api/v1/occupational-health/inbox")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class OccupationalHealthInboxController {

    private final ApplicationEventPublisher events;

    @PostMapping("/occupational-limit-exceeded")
    public ResponseEntity<ApiResponse<Void>> limitExceeded(
            @Valid @RequestBody OccupationalLimitExceededInput in) {
        events.publishEvent(new OccupationalLimitExceeded(
                EventEnvelope.of(in.organizationId()), in.sessionId(), in.segmentId(),
                in.workerId(), in.dose(), in.unit(), in.limit(), in.limitVersionId(),
                in.capturedAt(), in.detectedAt()));
        return accepted("OccupationalLimitExceeded");
    }

    @PostMapping("/exposure-session-closed")
    public ResponseEntity<ApiResponse<Void>> sessionClosed(
            @Valid @RequestBody ExposureSessionClosedInput in) {
        events.publishEvent(new ExposureSessionClosed(
                EventEnvelope.of(in.organizationId()), in.sessionId(), in.snapshotId(),
                in.workerId(), in.finalDose(), in.unit(), in.limit(), in.methodId(),
                in.coverage(), in.finalizedAt()));
        return accepted("ExposureSessionClosed");
    }

    @PostMapping("/rotation-suggested")
    public ResponseEntity<ApiResponse<Void>> rotationSuggested(
            @Valid @RequestBody RotationSuggestedInput in) {
        events.publishEvent(new RotationSuggested(
                EventEnvelope.of(in.organizationId()), in.suggestionId(), in.workerId(),
                in.candidateWorkerIds(), in.projectedExceedanceAt(), in.criticality(),
                in.rationale()));
        return accepted("RotationSuggested");
    }

    @PostMapping("/alert-evidence")
    public ResponseEntity<ApiResponse<Void>> alertEvidence(
            @Valid @RequestBody AlertEvidenceInput in) {
        events.publishEvent(new AlertEvidenceReported(
                EventEnvelope.of(in.organizationId()), in.kind(), in.alertId(),
                in.workerId(), in.sessionId(), in.alertType(), in.ssoResponsibleId(),
                in.reason(), in.occurredAt()));
        return accepted("AlertEvidenceReported");
    }

    /**
     * 202 y no 200: el hecho se acepta y se procesa, pero la respuesta no
     * describe su resultado — igual que haría una suscripción a un broker.
     */
    private ResponseEntity<ApiResponse<Void>> accepted(String eventName) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(eventName + " accepted", null));
    }
}
