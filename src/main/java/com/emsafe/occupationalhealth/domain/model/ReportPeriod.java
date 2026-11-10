package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * El período que cubre un reporte (informe §5.6.1).
 *
 * Invariantes del informe: `from` no posterior a `to`, y rango máximo de 366
 * días. El tope existe para que una exportación no pueda barrer años enteros de
 * datos sensibles en una sola llamada.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ReportPeriod {

    /** Un año bisiesto completo. */
    public static final long MAX_DAYS = 366;

    @Column(name = "period_from", nullable = false)
    private LocalDate from;

    @Column(name = "period_to", nullable = false)
    private LocalDate to;

    public static ReportPeriod of(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new MitigationUnprocessableException("Both 'from' and 'to' dates are required");
        }
        if (from.isAfter(to)) {
            throw new MitigationUnprocessableException(
                    "Period start cannot be after its end: " + from + " → " + to);
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new MitigationUnprocessableException(
                    "Period cannot span more than " + MAX_DAYS + " days");
        }
        return new ReportPeriod(from, to);
    }

    /** Días que cubre, contando ambos extremos. */
    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    /** Límites como instantes, para consultar tablas fechadas con DATETIME. */
    public java.time.LocalDateTime startOfPeriod() {
        return from.atStartOfDay();
    }

    public java.time.LocalDateTime endOfPeriod() {
        return to.plusDays(1).atStartOfDay().minusNanos(1);
    }

    @Override
    public String toString() {
        return from + "/" + to;
    }
}
