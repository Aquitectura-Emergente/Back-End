package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

import java.util.UUID;

/** Arranque del dispositivo. El firmware no lo envía: se deriva en la ingesta. */
public record BootId(String value) {
    public BootId {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new BadRequestException("Boot id must have between 1 and 64 characters");
        }
    }

    public static BootId random() {
        return new BootId(UUID.randomUUID().toString());
    }
}
