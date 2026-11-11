package com.emsafe.aiforecasting.interfaces.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cuerpos de los eventos del inbox. Aquí solo se valida formato y presencia (400); las reglas
 * de dominio, como límite ≤ 0 o captura antes del inicio de sesión, se validan en el dominio
 * (422). Los índices admiten como máximo 6 decimales, la escala de la base de datos.
 */
public final class ForecastingRequests {

    private ForecastingRequests() {
    }

    @Schema(description = "Alta/actualización del snapshot de exposición de una sesión abierta")
    public record ExposureSnapshot(
            @Schema(example = "snap-0001") @NotBlank(message = "eventId is required")
            @Size(max = 100, message = "eventId must be at most 100 characters") String eventId,
            @Size(max = 100, message = "correlationId must be at most 100 characters") String correlationId,
            @Schema(example = "8") @NotNull(message = "organizationId is required")
            @Positive(message = "organizationId must be positive") Long organizationId,
            @Schema(example = "101") @NotNull(message = "workerId is required")
            @Positive(message = "workerId must be positive") Long workerId,
            @Schema(example = "S-101") @NotBlank(message = "sessionId is required")
            @Size(max = 64, message = "sessionId must be at most 64 characters") String sessionId,
            @Schema(example = "Z-1", description = "Opcional; si se omite se conserva el actual")
            @Size(max = 64, message = "zoneId must be at most 64 characters") String zoneId,
            @Schema(example = "10", description = "Opcional; si se omite se conserva el actual")
            @Positive(message = "positionId must be positive") Long positionId,
            @Schema(example = "6000", description = "Índice acumulado en uT*s")
            @NotNull(message = "accumulatedIndex is required")
            @Digits(integer = 32, fraction = 6, message = "accumulatedIndex allows at most 6 decimals")
            BigDecimal accumulatedIndex,
            @Schema(example = "12000", description = "Límite vigente en uT*s")
            @NotNull(message = "limitIndex is required")
            @Digits(integer = 32, fraction = 6, message = "limitIndex allows at most 6 decimals")
            BigDecimal limitIndex,
            @Schema(example = "2026-11-10T08:00:00Z") @NotNull(message = "sessionStartedAt is required")
            Instant sessionStartedAt,
            @Schema(example = "2026-11-10T16:00:00Z",
                    description = "Opcional; sin él se usa sessionStartedAt + default-shift-hours")
            Instant plannedShiftEnd,
            @Schema(example = "2026-11-10T12:00:00Z") @NotNull(message = "capturedAt is required")
            Instant capturedAt) {
    }

    @Schema(description = "Evento ExposureWarningRaised de Personal Dosimetry: dispara una evaluación")
    public record ExposureWarningRaised(
            @Schema(example = "warn-0001") @NotBlank(message = "eventId is required")
            @Size(max = 100, message = "eventId must be at most 100 characters") String eventId,
            @Size(max = 100, message = "correlationId must be at most 100 characters") String correlationId,
            @Schema(example = "8") @NotNull(message = "organizationId is required")
            @Positive(message = "organizationId must be positive") Long organizationId,
            @Schema(example = "S-101") @NotBlank(message = "sessionId is required")
            @Size(max = 64, message = "sessionId must be at most 64 characters") String sessionId,
            @Schema(example = "101") @NotNull(message = "workerId is required")
            @Positive(message = "workerId must be positive") Long workerId,
            @Schema(example = "7200") @NotNull(message = "accumulatedIndex is required")
            @Digits(integer = 32, fraction = 6, message = "accumulatedIndex allows at most 6 decimals")
            BigDecimal accumulatedIndex,
            @Schema(example = "12000") @NotNull(message = "limitIndex is required")
            @Digits(integer = 32, fraction = 6, message = "limitIndex allows at most 6 decimals")
            BigDecimal limitIndex,
            @Schema(example = "7") Long limitVersionId,
            @Schema(example = "2026-11-10T12:00:00Z") @NotNull(message = "capturedAt is required")
            Instant capturedAt,
            @Schema(example = "MAGNETIC_TIME_INDEX_V1") @NotBlank(message = "methodId is required")
            String methodId,
            @Schema(example = "uT*s") @NotBlank(message = "unit is required") String unit) {
    }

    @Schema(description = "Cambio de zona, puesto o fin de turno. Los campos omitidos se conservan.")
    public record ShiftUpdated(
            @Schema(example = "shift-0001") @NotBlank(message = "eventId is required")
            @Size(max = 100, message = "eventId must be at most 100 characters") String eventId,
            @Size(max = 100, message = "correlationId must be at most 100 characters") String correlationId,
            @Schema(example = "8") @NotNull(message = "organizationId is required")
            @Positive(message = "organizationId must be positive") Long organizationId,
            @Schema(example = "101") @NotNull(message = "workerId is required")
            @Positive(message = "workerId must be positive") Long workerId,
            @Schema(example = "S-101") @NotBlank(message = "sessionId is required")
            @Size(max = 64, message = "sessionId must be at most 64 characters") String sessionId,
            @Schema(example = "Z-2") @Size(max = 64, message = "zoneId must be at most 64 characters") String zoneId,
            @Schema(example = "30") @Positive(message = "positionId must be positive") Long positionId,
            @Schema(example = "2026-11-10T18:00:00Z") Instant plannedShiftEnd) {
    }
}
