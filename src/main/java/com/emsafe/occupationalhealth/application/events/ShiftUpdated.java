package com.emsafe.occupationalhealth.application.events;

import java.time.LocalDateTime;

/**
 * Mitigation → Dosimetry (informe §5.6.3).
 *
 * El camino de vuelta de la relación Partnership: la rotación se aplicó y
 * Personal Dosimetry debe conservar el acumulado y pasar a aplicar el límite del
 * puesto nuevo. Los campos son exactamente los que ese contexto espera; cambiar
 * uno rompe el acuerdo. Añadir uno al final no: los consumidores que ya existen
 * siguen compilando.
 *
 * <p>{@code newZoneId} se añadió para AI Forecasting, que recalcula la
 * evaluación activa al recibir el evento y necesita la zona destino: sin ella
 * conservaría la de origen —justo la que se acaba de abandonar por peligrosa— y
 * buscaría candidatos de rotación en el sitio equivocado.</p>
 */
public record ShiftUpdated(
        EventEnvelope envelope,
        String sessionId,
        Long actionId,
        Long workerId,
        Long previousPositionId,
        Long newPositionId,
        Long newZoneId,
        LocalDateTime effectiveAt,
        Long approvedBy) {
}
