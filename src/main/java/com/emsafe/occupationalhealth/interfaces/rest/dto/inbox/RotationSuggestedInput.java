package com.emsafe.occupationalhealth.interfaces.rest.dto.inbox;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/** Cuerpo del hecho RotationSuggested — ver OccupationalHealthInboxController. */
public record RotationSuggestedInput(
        @NotNull(message = "organizationId is required") Long organizationId,
        @NotNull(message = "workerId is required") Long workerId,
        String suggestionId,
        List<Long> candidateWorkerIds,
        LocalDateTime projectedExceedanceAt,
        String criticality,
        String rationale
) {
}
