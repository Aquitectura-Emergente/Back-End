package com.emsafe.occupationalhealth.interfaces.rest.dto.inbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Cuerpo del hecho ExposureSessionClosed — ver OccupationalHealthInboxController. */
public record ExposureSessionClosedInput(
        @NotNull(message = "organizationId is required") Long organizationId,
        @NotNull(message = "workerId is required") Long workerId,
        @NotBlank(message = "sessionId is required") String sessionId,
        String snapshotId,
        BigDecimal finalDose,
        String unit,
        BigDecimal limit,
        String methodId,
        String coverage,
        @NotNull(message = "finalizedAt is required") LocalDateTime finalizedAt
) {
}
