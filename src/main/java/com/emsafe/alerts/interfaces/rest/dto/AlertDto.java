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
        String sensor
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
                a.getSensor()
        );
    }
}
