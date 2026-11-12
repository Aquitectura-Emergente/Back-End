package com.emsafe.occupationalhealth.application.events.inbound;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Dosimetry → Mitigation (informe §5.6.3).
 *
 * La jornada de exposición quedó consolidada. Alimenta la proyección local que
 * usan los reportes y cierra el turno asociado.
 */
public record ExposureSessionClosed(
        EventEnvelope envelope,
        String sessionId,
        String snapshotId,
        Long workerId,
        BigDecimal finalDose,
        String unit,
        BigDecimal limit,
        String methodId,
        String coverage,
        LocalDateTime finalizedAt) {
}
