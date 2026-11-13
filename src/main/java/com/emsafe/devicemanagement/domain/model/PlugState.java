package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

import java.time.Instant;

/** Estado real del enchufe según el Edge hogar. {@code commandId} es null si no lo originó una orden. */
public record PlugState(PlugPower power, String commandId, PlugStateSource source, Instant changedAt) {
    public PlugState {
        if (power == null || source == null || changedAt == null) {
            throw new BadRequestException("Plug state is incomplete");
        }
        if (commandId != null && (commandId.isBlank() || commandId.length() > 64)) {
            throw new BadRequestException("Plug command id is invalid");
        }
    }
}
