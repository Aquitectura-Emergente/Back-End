package com.emsafe.aiforecasting.domain.model;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

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

    /** La sesión pertenece a esta organización y operario; si no, es un conflicto (409). */
    public void ensureBelongsTo(Long expectedOrganizationId, Long expectedWorkerId) {
        if (!Objects.equals(organizationId, expectedOrganizationId) || !Objects.equals(workerId, expectedWorkerId)) {
            throw ForecastingRuleException.conflict("session " + sessionId
                    + " belongs to another worker or organization");
        }
    }

    /**
     * Integra un snapshot entrante de la misma sesión. Gana la captura más reciente:
     * uno más antiguo se ignora. Zona, puesto y fin de turno omitidos se conservan.
     */
    public WorkerExposureSnapshot merge(WorkerExposureSnapshot incoming) {
        requirePresent(incoming, "incoming");
        if (!sessionId.equals(incoming.sessionId())) {
            throw ForecastingRuleException.conflict("cannot merge snapshots of different sessions");
        }
        ensureBelongsTo(incoming.organizationId(), incoming.workerId());
        if (incoming.capturedAt().isBefore(capturedAt)) {
            return this;
        }
        return new WorkerExposureSnapshot(organizationId, workerId, sessionId,
                incoming.zoneId() != null ? incoming.zoneId() : zoneId,
                incoming.positionId() != null ? incoming.positionId() : positionId,
                incoming.accumulatedIndex(), incoming.limitIndex(), incoming.sessionStartedAt(),
                incoming.plannedShiftEnd() != null ? incoming.plannedShiftEnd() : plannedShiftEnd,
                incoming.capturedAt(), incoming.updatedAt());
    }

    /** Nueva lectura del índice (p. ej. ExposureWarningRaised). Una captura más antigua se ignora. */
    public WorkerExposureSnapshot withCapture(BigDecimal newIndex, BigDecimal newLimit,
                                              Instant newCapturedAt, Instant now) {
        requirePresent(newCapturedAt, "capturedAt");
        if (newCapturedAt.isBefore(capturedAt)) {
            return this;
        }
        return new WorkerExposureSnapshot(organizationId, workerId, sessionId, zoneId, positionId,
                newIndex, newLimit, sessionStartedAt, plannedShiftEnd, newCapturedAt, now);
    }

    /** Cambio de zona, puesto o fin de turno (ShiftUpdated). Los valores nulos se conservan. */
    public WorkerExposureSnapshot withShift(String newZoneId, Long newPositionId,
                                            Instant newPlannedShiftEnd, Instant now) {
        return new WorkerExposureSnapshot(organizationId, workerId, sessionId,
                newZoneId != null ? newZoneId : zoneId,
                newPositionId != null ? newPositionId : positionId,
                accumulatedIndex, limitIndex, sessionStartedAt,
                newPlannedShiftEnd != null ? newPlannedShiftEnd : plannedShiftEnd, capturedAt, now);
    }
}
