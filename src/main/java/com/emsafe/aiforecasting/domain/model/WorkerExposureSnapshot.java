package com.emsafe.aiforecasting.domain.model;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;
import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/**
 * Modelo de lectura local: estado de exposición de una sesión abierta.
 * Se alimenta por el inbox; los identificadores son de integración, sin FK a otros contextos.
 *
 * @param zoneId          zona de trabajo, opcional (sin zona no hay candidatos de rotación)
 * @param positionId      puesto actual, opcional
 * @param plannedShiftEnd fin de turno planificado, opcional (se usa el turno por defecto)
 */
public record WorkerExposureSnapshot(Long organizationId,
                                     Long workerId,
                                     String sessionId,
                                     String zoneId,
                                     Long positionId,
                                     BigDecimal accumulatedIndex,
                                     BigDecimal limitIndex,
                                     Instant sessionStartedAt,
                                     Instant plannedShiftEnd,
                                     Instant capturedAt,
                                     Instant updatedAt) {

    public WorkerExposureSnapshot {
        requirePresent(organizationId, "organizationId");
        requirePresent(workerId, "workerId");
        requireText(sessionId, "sessionId");
        requirePresent(accumulatedIndex, "accumulatedIndex");
        requirePresent(limitIndex, "limitIndex");
        requirePresent(sessionStartedAt, "sessionStartedAt");
        requirePresent(capturedAt, "capturedAt");
        requirePresent(updatedAt, "updatedAt");
        if (accumulatedIndex.signum() < 0) {
            throw ForecastingRuleException.invalid("accumulatedIndex must not be negative");
        }
    }

    /** Fin de turno planificado o, si no llegó, inicio de sesión + turno por defecto. */
    public Instant shiftEnd(Duration defaultShift) {
        return plannedShiftEnd != null ? plannedShiftEnd : sessionStartedAt.plus(defaultShift);
    }

    /** Copia con nueva zona, puesto y fin de turno (evento ShiftUpdated). */
    public WorkerExposureSnapshot withShift(String newZoneId, Long newPositionId,
                                            Instant newPlannedShiftEnd, Instant now) {
        return new WorkerExposureSnapshot(organizationId, workerId, sessionId, newZoneId, newPositionId,
                accumulatedIndex, limitIndex, sessionStartedAt, newPlannedShiftEnd, capturedAt, now);
    }
}
