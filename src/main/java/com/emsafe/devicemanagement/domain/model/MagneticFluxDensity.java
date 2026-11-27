package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Densidad de flujo magnético en µT, con 3 decimales (la escala con que se
 * guarda la lectura). Se redondea al crearla porque el valor llega como
 * {@code double} por MQTT y puede traer ruido binario (0.33999999…): sin esto,
 * lo guardado y lo que se envía a Personal Dosimetry no coincidían y esta
 * rechazaba la lectura por exceso de decimales.
 */
public record MagneticFluxDensity(BigDecimal microtesla) {
    public static final int SCALE = 3;

    public MagneticFluxDensity {
        if (microtesla == null || microtesla.signum() < 0) {
            throw new BadRequestException("Magnetic flux density must be zero or positive");
        }
        microtesla = microtesla.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static MagneticFluxDensity ofMicrotesla(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new BadRequestException("Magnetic flux density must be a finite number");
        }
        return new MagneticFluxDensity(BigDecimal.valueOf(value));
    }
}
