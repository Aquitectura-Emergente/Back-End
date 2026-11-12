package com.emsafe.occupationalhealth.application.events;

import java.time.LocalDateTime;

/**
 * Mitigation → Dosimetry (informe §5.6.3).
 *
 * El camino de vuelta de la relación Partnership: la rotación se aplicó y
 * Personal Dosimetry debe conservar el acumulado y pasar a aplicar el límite del
 * puesto nuevo. Los campos son exactamente los que ese contexto espera; cambiar
 * uno rompe el acuerdo.
 */
public record ShiftUpdated(
        EventEnvelope envelope,
        String sessionId,
        Long actionId,
        Long workerId,
        Long previousPositionId,
        Long newPositionId,
        LocalDateTime effectiveAt,
        Long approvedBy) {
}
