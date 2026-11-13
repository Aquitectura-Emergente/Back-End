package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

import java.time.Duration;

/** Tiempo sin noticias tras el cual un dispositivo ONLINE pasa a OFFLINE. */
public record InactivityTimeout(long seconds) {
    public static final InactivityTimeout DEFAULT = new InactivityTimeout(300);

    public InactivityTimeout {
        if (seconds < 10 || seconds > 86_400) {
            throw new BadRequestException("Inactivity timeout must be between 10 s and 24 h");
        }
    }

    public Duration duration() {
        return Duration.ofSeconds(seconds);
    }
}
