package com.emsafe.occupationalhealth.application.events.inbound;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Dosimetry → Mitigation (informe §5.6.3).
 *
 * El acumulado del operario superó el límite aplicable a su puesto. Este
 * contexto no lo recalcula ni lo discute: abre una medida pendiente de decisión.
 *
 * `capturedAt` es cuándo se midió y `detectedAt` cuándo se confirmó la
 * excedencia. Los dos viajan porque una lectura sincronizada de forma diferida
 * puede detectarse mucho después del suceso, y la medida debe conservar la hora
 * real.
 *
 * `unit` no aparece en la tabla de campos del informe, que lista los
 * "principales", pero es imprescindible: una dosis sin unidad no se puede
 * comparar con nada.
 */
public record OccupationalLimitExceeded(
        EventEnvelope envelope,
        String sessionId,
        String segmentId,
        Long workerId,
        BigDecimal dose,
        String unit,
        BigDecimal limit,
        String limitVersionId,
        LocalDateTime capturedAt,
        LocalDateTime detectedAt) {
}
