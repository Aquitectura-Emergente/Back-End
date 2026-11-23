package com.emsafe.occupationalhealth.application.events.inbound;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Dosimetry → Occupational Health (informe §5.4.3, §5.5.3).
 *
 * <p>El acumulado del operario pasó la fracción de aviso de su límite
 * ({@code warningRatio} de la jornada), pero todavía no el límite. Este contexto
 * lo usa para el aviso preventivo al operario; a diferencia de
 * {@link OccupationalLimitExceeded}, <b>no abre una medida</b> de mitigación.</p>
 *
 * <p>Se agregó el 2026-11-22. Hasta entonces el relay de Personal Dosimetry
 * entregaba este hecho solo a AI Forecasting (como
 * {@code ExposureWarningRaisedCommand}). Los campos son los mismos que los de
 * {@link OccupationalLimitExceeded} porque Dosimetry emite los dos con el mismo
 * payload; {@code limitVersionId} viaja como texto, igual que en aquel.</p>
 */
public record ExposureWarningRaised(
        EventEnvelope envelope,
        String sessionId,
        Long workerId,
        BigDecimal dose,
        String unit,
        BigDecimal limit,
        String limitVersionId,
        String methodId,
        LocalDateTime capturedAt,
        LocalDateTime detectedAt) {
}
