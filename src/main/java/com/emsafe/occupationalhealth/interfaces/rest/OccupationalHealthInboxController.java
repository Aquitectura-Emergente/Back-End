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
 * Bus. Esta solución no tiene broker, así que este controller los inyecta a
 * mano: publica el evento en proceso y los handlers reaccionan exactamente como
 * reaccionarían ante el broker, inbox incluido.
 *
 * Es ADMIN-only a propósito: no es una capacidad del negocio. Cuando exista
 * broker o los módulos publiquen en proceso, **este controller se borra** y no
 * hay que tocar ningún handler.
 *
 * ── QUÉ SIGUE NECESITÁNDOLO Y QUÉ NO (2026-11) ───────────────────────────────
 * Cuando se escribió esto, los contextos que publican estos hechos no existían.
 * Ya existen, y el estado es desigual:
 *
 * - {@code rotation-suggested} YA TIENE PRODUCTOR REAL: AI Forecasting lo
 *   escribe en su outbox y {@code ForecastingOutboxRelay} lo entrega. Este
 *   endpoint queda solo para pruebas y demos.
 * - {@code occupational-limit-exceeded} y {@code exposure-session-closed}
 *   siguen entrando SOLO por aquí. Personal Dosimetry los emite a su propio
 *   outbox ({@code personal_dosimetry_outbox}) y nadie lo drena, porque su
 *   payload no encaja con estos DTO: manda {@code accumulatedIndex} y
 *   {@code limitIndex} donde aquí se esperan {@code dose} y {@code limit}, y no
 *   manda {@code segmentId}, {@code snapshotId} ni {@code methodId}. Decidir esa
 *   equivalencia es cosa del dueño de ese contexto: de ella dependen las cifras
 *   de los reportes de cumplimiento.
 * - {@code alert-evidence} tampoco tiene productor.
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
                in.methodId(), in.capturedAt(), in.detectedAt()));
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
