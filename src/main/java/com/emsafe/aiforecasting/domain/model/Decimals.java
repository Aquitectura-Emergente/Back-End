package com.emsafe.aiforecasting.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/** Aritmética común del contexto: escala 6, {@link RoundingMode#HALF_UP}. */
public final class Decimals {

    public static final int SCALE = 6;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Decimals() {
    }

    public static BigDecimal scaled(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        return dividend.divide(divisor, SCALE, ROUNDING);
    }

    /** Duración en segundos (con fracción de nanosegundos) a escala 6. */
    public static BigDecimal seconds(Duration duration) {
        return scaled(BigDecimal.valueOf(duration.getSeconds())
                .add(BigDecimal.valueOf(duration.getNano(), 9)));
    }

    /** Segundos fraccionarios a {@link Duration}, redondeando a milisegundos. */
    public static Duration toDuration(BigDecimal seconds) {
        long millis = seconds.movePointRight(3).setScale(0, ROUNDING).longValueExact();
        return Duration.ofMillis(millis);
    }

    /** Ratio a porcentaje con 2 decimales, sin dependencia de locale. */
    public static String percent(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(2, ROUNDING).toPlainString();
    }
}
