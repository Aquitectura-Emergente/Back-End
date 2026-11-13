package com.emsafe.personaldosimetry.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Operational demonstration index. It does not represent a certified occupational dose. */
public final class ExposureIndex {
    public static final String METHOD = "MAGNETIC_TIME_INDEX_V1";
    public static final String UNIT = "uT*s";

    private ExposureIndex() {}

    public static BigDecimal contribution(BigDecimal fieldMicrotesla, Instant from, Instant to) {
        decimal(fieldMicrotesla, 12, 6, "fieldMicrotesla");
        long micros = durationMicros(from, to);
        return fieldMicrotesla.multiply(BigDecimal.valueOf(micros, 6));
    }

    public static long durationMicros(Instant from, Instant to) {
        timestamp(from);
        timestamp(to);
        if (!to.isAfter(from)) throw DosimetryRuleException.invalid("capturedTo must be after capturedFrom");
        try {
            return ChronoUnit.MICROS.between(from, to);
        } catch (ArithmeticException ex) {
            throw DosimetryRuleException.invalid("Capture interval is too large");
        }
    }

    public static BigDecimal percentage(BigDecimal amount, BigDecimal limit) {
        return amount.multiply(BigDecimal.valueOf(100)).divide(limit, 2, RoundingMode.HALF_UP);
    }

    public static void decimal(BigDecimal value, int integerDigits, int fractionDigits, String name) {
        if (value == null || value.signum() < 0) {
            throw DosimetryRuleException.invalid(name + " must be non-negative");
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (Math.max(0, normalized.scale()) > fractionDigits
                || (long) normalized.precision() - normalized.scale() > integerDigits) {
            throw DosimetryRuleException.invalid(name + " exceeds supported precision");
        }
    }

    public static void timestamp(Instant value) {
        if (value == null || value.getNano() % 1_000 != 0) {
            throw DosimetryRuleException.invalid("Timestamps must have at most microsecond precision");
        }
        if (value.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || value.isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) {
            throw DosimetryRuleException.invalid("Timestamp is outside the database range");
        }
    }

    public static void positiveId(Long id, String name) {
        if (id == null || id <= 0) throw DosimetryRuleException.invalid(name + " must be positive");
    }
}
