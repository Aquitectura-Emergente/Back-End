package com.emsafe.aiforecasting.interfaces.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Representaciones de respuesta del contexto AI Forecasting. */
public final class ForecastingResources {

    private ForecastingResources() {
    }

    @Schema(description = "Resultado de procesar un evento del inbox")
    public record InboxResult(
            String eventId,
            @Schema(description = "true si el eventId ya estaba procesado: no hubo efectos") boolean duplicate,
            @Schema(description = "Evaluación creada o recalculada, si la hubo") String assessmentId) {
    }

    @Schema(description = "Evaluación preventiva. Es una proyección: no afirma que el límite se haya excedido.")
    public record Assessment(
            String id,
            Long organizationId,
            Long workerId,
            String sessionId,
            @Schema(allowableValues = {"PROJECTED", "ASSESSED", "RECOMMENDED", "CLOSED"}) String status,
            @Schema(allowableValues = {"LOW", "MODERATE", "HIGH", "CRITICAL"}) String criticality,
            Projection projection,
            Recommendation recommendation,
            Redaction redaction,
            ModelVersion modelVersion,
            Long limitVersionId,
            String triggerEventId,
            Instant assessedAt,
            Instant closedAt,
            @Schema(allowableValues = {"SUPERSEDED", "SESSION_ENDED"}) String closeReason,
            @Schema(description = "Siempre true: este contexto proyecta, no decide excedencias") boolean isProjection) {
    }

    public record Projection(
            BigDecimal currentIndex,
            BigDecimal limitIndex,
            @Schema(description = "uT*s por segundo") BigDecimal accumulationRate,
            BigDecimal projectedIndexAtShiftEnd,
            BigDecimal remainingMargin,
            BigDecimal consumedRatio,
            BigDecimal projectedRatio,
            Instant estimatedExceedanceAt,
            Instant shiftEnd,
            @Schema(example = "uT*s") String unit) {
    }

    public record Recommendation(
            @Schema(allowableValues = {"ROTATION", "RESCHEDULE_TASK"}) String type,
            Long sourceWorkerId,
            Long targetWorkerId,
            Long sourcePositionId,
            Long targetPositionId,
            BigDecimal estimatedBenefitIndex,
            String reason) {
    }

    public record Redaction(
            String text,
            @Schema(allowableValues = {"LLM", "FALLBACK"}) String generatedBy,
            Instant generatedAt) {
    }

    public record ModelVersion(String modelId, String version, @Schema(example = "LINEAR_RATE_V1") String code) {
    }

    @Schema(description = "Evento saliente del outbox con su sobre común")
    public record OutboxEvent(
            String eventId,
            @Schema(allowableValues = {"RotationSuggested", "PredictiveRiskDetected"}) String eventType,
            int schemaVersion,
            Long organizationId,
            String aggregateId,
            Instant occurredAt,
            String correlationId,
            String causationId,
            @Schema(description = "Cuerpo del evento. RotationSuggested es compatible con el inbox de "
                    + "Occupational Health (projectedExceedanceAt como LocalDateTime UTC, sin zona).")
            Map<String, Object> payload,
            Instant publishedAt) {
    }
}
