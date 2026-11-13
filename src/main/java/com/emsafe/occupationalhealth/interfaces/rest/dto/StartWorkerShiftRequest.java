package com.emsafe.occupationalhealth.interfaces.rest.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Apertura del turno de un operario.
 *
 * Corresponde al comando añadido que el informe no cubre — ver
 * StartWorkerShiftCommandHandler.
 */
public record StartWorkerShiftRequest(
        @NotNull(message = "jobPositionId is required") Long jobPositionId,
        @NotNull(message = "plantZoneId is required") Long plantZoneId,
        String exposureSessionId
) {
}
