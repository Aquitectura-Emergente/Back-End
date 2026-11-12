package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Marca un tramo del reporte sincronizado de forma diferida o con cobertura
 * incompleta (informe §5.6.1, regla 8).
 *
 * No invalida la cifra: la acompaña. Quien audite el reporte ve qué partes
 * vienen de datos que llegaron tarde o incompletos, en vez de tener que
 * suponerlo.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TraceabilityNote {

    public static final String INCOMPLETE_COVERAGE = "Incomplete coverage reported by dosimetry";
    public static final String DEFERRED_SYNC = "Reading synchronized after the fact";

    @Embedded
    private ShiftInterval interval;

    @Column(nullable = false, length = 300)
    private String cause;

    public static TraceabilityNote of(LocalDateTime from, LocalDateTime to, String cause) {
        if (cause == null || cause.isBlank()) {
            throw new MitigationRuleException("A traceability note requires a cause");
        }
        ShiftInterval span = ShiftInterval.openAt(from);
        if (to != null) {
            span = span.closedAt(to);
        }
        return new TraceabilityNote(span, cause.trim());
    }

    /** Nota de cobertura incompleta sobre una jornada ya cerrada. */
    public static TraceabilityNote forIncompleteCoverage(ClosedSessionRecord session) {
        return of(session.getFinalizedAt(), null,
                INCOMPLETE_COVERAGE + " (" + session.getCoverage() + ")");
    }
}
