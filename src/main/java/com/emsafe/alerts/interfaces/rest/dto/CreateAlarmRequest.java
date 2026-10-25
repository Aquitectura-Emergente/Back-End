package com.emsafe.alerts.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateAlarmRequest(
        @NotBlank String type,
        String icon,
        @NotBlank String title,
        String description,
        String relativeTime,

        /** all | specific */
        String recipientType,

        /** Selected client ids when recipientType = specific. */
        List<Long> clientIds,

        String sensor
) {}
