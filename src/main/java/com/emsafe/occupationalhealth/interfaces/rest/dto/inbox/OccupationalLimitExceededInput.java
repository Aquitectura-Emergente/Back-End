package com.emsafe.occupationalhealth.interfaces.rest.dto.inbox;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Cuerpo del hecho OccupationalLimitExceeded — ver OccupationalHealthInboxController. */
public record OccupationalLimitExceededInput(
        @NotNull(message = "organizationId is required") Long organizationId,
        @NotNull(message = "workerId is required") Long workerId,
        String sessionId,
        String segmentId,
        BigDecimal dose,
        String unit,
        BigDecimal limit,
        String limitVersionId,
        LocalDateTime capturedAt,
        LocalDateTime detectedAt
) {
}
