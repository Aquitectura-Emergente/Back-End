package com.emsafe.clientportal.interfaces.rest.dto;

/**
 * An alert derived from a client reading that exceeded the caution threshold.
 * (The global Alert entity has no client relationship, so client alerts are
 * computed from the client's own readings.)
 */
public record ClientAlertDto(
        Long id,
        String type,
        String level,
        String title,
        String description,
        Double value,
        Long deviceId,
        String deviceName,
        String time,
        String recordedAt   // precise ISO timestamp (for relative "36 min ago" display)
) {}
