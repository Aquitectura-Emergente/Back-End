package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;

import java.math.BigDecimal;

/**
 * Umbral instantáneo del dosímetro en µT, definido por el responsable de SSO.
 * Se rechaza lo mismo que rechaza el Edge (≤ 0 o desde la saturación del
 * sensor), para no publicar una configuración que el Edge descartaría.
 */
public record AlertThreshold(BigDecimal microtesla) {
    public static final BigDecimal SATURATION_UT = new BigDecimal("790");
    public static final AlertThreshold DEFAULT = new AlertThreshold(new BigDecimal("300"));

    public AlertThreshold {
        if (microtesla == null || microtesla.signum() <= 0 || microtesla.compareTo(SATURATION_UT) >= 0) {
            throw new BadRequestException("Alert threshold must be greater than 0 and lower than "
                    + SATURATION_UT + " uT");
        }
        // Siempre con al menos un decimal: el contrato lo publica como 300.0
        microtesla = microtesla.stripTrailingZeros();
        if (microtesla.scale() < 1) microtesla = microtesla.setScale(1);
    }

    public static AlertThreshold of(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new BadRequestException("Alert threshold must be a finite number");
        }
        return new AlertThreshold(BigDecimal.valueOf(value));
    }
}
