package com.emsafe.occupationalhealth.interfaces.rest.dto;

import jakarta.validation.constraints.NotNull;

/** Reubicación manual decidida por el SSO (POST /mitigation-actions/relocations). */
public record RegisterRelocationRequest(
        @NotNull(message = "workerId is required") Long workerId,
        @NotNull(message = "toPositionId is required") Long toPositionId,
        @NotNull(message = "toZoneId is required") Long toZoneId,
        String justification
) {
}
