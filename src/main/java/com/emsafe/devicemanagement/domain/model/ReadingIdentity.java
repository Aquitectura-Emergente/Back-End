package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

import java.time.Instant;

/**
 * Identidad de una lectura en la ingesta. Un reintento MQTT o un reenvío del
 * buffer trae los mismos tres valores, así que no se duplica.
 */
public record ReadingIdentity(DeviceSerial device, long sequence, Instant capturedAt) {
    public ReadingIdentity {
        if (device == null || capturedAt == null) throw new BadRequestException("Reading identity is incomplete");
        if (sequence < 0) throw new BadRequestException("Sequence must be zero or positive");
    }
}
