package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

import java.time.Duration;

/** Intervalo de muestreo; marca el inicio de la ventana que cubre cada lectura. */
public record SamplingInterval(long millis) {
    public static final SamplingInterval DEFAULT = new SamplingInterval(1000);

    public SamplingInterval {
        if (millis < 100 || millis > 3_600_000) {
            throw new BadRequestException("Sampling interval must be between 100 ms and 1 h");
        }
    }

    public Duration duration() {
        return Duration.ofMillis(millis);
    }
}
