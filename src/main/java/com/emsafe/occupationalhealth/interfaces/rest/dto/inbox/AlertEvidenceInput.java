package com.emsafe.occupationalhealth.interfaces.rest.dto.inbox;

import com.emsafe.occupationalhealth.application.events.inbound.AlertEvidenceReported;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** Cuerpo de AlertAcknowledged / AlertEscalated — ver OccupationalHealthInboxController. */
public record AlertEvidenceInput(
        @NotNull(message = "organizationId is required") Long organizationId,
        @NotNull(message = "kind is required (ACKNOWLEDGED or ESCALATED)")
        AlertEvidenceReported.Kind kind,
        @NotBlank(message = "alertId is required") String alertId,
        Long workerId,
        String sessionId,
        String alertType,
        Long ssoResponsibleId,
        String reason,
        @NotNull(message = "occurredAt is required") LocalDateTime occurredAt
) {
}
