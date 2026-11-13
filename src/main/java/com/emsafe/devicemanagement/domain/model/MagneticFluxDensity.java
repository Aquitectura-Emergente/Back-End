package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

import java.math.BigDecimal;

/** Densidad de flujo magnético en µT. */
public record MagneticFluxDensity(BigDecimal microtesla) {
    public MagneticFluxDensity {
        if (microtesla == null || microtesla.signum() < 0) {
            throw new BadRequestException("Magnetic flux density must be zero or positive");
        }
    }

    public static MagneticFluxDensity ofMicrotesla(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new BadRequestException("Magnetic flux density must be a finite number");
        }
        return new MagneticFluxDensity(BigDecimal.valueOf(value));
    }
}
