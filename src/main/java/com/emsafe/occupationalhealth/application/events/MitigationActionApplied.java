package com.emsafe.occupationalhealth.application.events;

import com.emsafe.occupationalhealth.domain.model.MitigationType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mitigation → interesados (informe §5.6.3).
 *
 * AI Forecasting puede suscribirse para recalcular las proyecciones de los
 * operarios afectados (paso 8 del Escenario 3).
 */
public record MitigationActionApplied(
        EventEnvelope envelope,
        Long actionId,
        MitigationType type,
        List<Long> workerIds,
        LocalDateTime appliedAt,
        Long approvedBy) {
}
