package com.emsafe.alerts.interfaces.rest.dto;

import com.emsafe.alerts.domain.model.Alert;

public record AlertDto(
        Long id,
        String type,
        String icon,
        String title,
        String description,
        String time,
        Boolean resolved,
        String resolvedAt,
        String recipientType,
        String clientName,
        String sensor,

        // ─── Alerta de operario (V23) ─────────────────────────────────────────
        // Nulos en una alarma de cliente. Añadir campos a esta respuesta es
        // aditivo: el front y el móvil ignoran los que no conocen.
        Long organizationId,
        Long workerId,
        String sessionId,
        String acknowledgedAt,
        String escalatedAt,
        Long ssoResponsibleId,
        String escalationReason
) {
    public static AlertDto from(Alert a) {
        return new AlertDto(
                a.getId(),
                a.getType(),
                a.getIcon(),
                a.getTitle(),
                a.getDescription(),
                a.getRelativeTime(),
                a.getResolved(),
                a.getResolvedAt() != null ? a.getResolvedAt().toString() : null,
                a.getRecipientType(),
                a.getClientName(),
                a.getSensor(),
                a.getOrganizationId(),
                a.getWorkerId(),
                a.getSessionId(),
                a.getAcknowledgedAt() != null ? a.getAcknowledgedAt().toString() : null,
                a.getEscalatedAt() != null ? a.getEscalatedAt().toString() : null,
                a.getSsoResponsibleId(),
                a.getEscalationReason()
        );
    }
}
