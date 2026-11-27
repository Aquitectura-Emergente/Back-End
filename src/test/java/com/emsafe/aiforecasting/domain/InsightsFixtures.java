package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.PositionStats;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.ProjectedCritical;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.Recommendations;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.Totals;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WarningWorker;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WeekStats;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WorkerStats;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.RecommendationType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Resúmenes de ejemplo para el "Análisis con IA". Los IDs, el nombre del puesto y los IDs de sesión
 * y evaluación son distintivos para poder comprobar que no llegan al prompt.
 */
public final class InsightsFixtures {

    public static final long ORG = 424242L;
    public static final long W1 = 987001L, W2 = 987002L, W3 = 987003L, W4 = 987004L;
    public static final long P1 = 556677L, P2 = 556688L;
    public static final String POSITION_NAME = "Prensa Secreta";
    public static final String SESSION_ID = "S-SECRET-1";
    public static final String ASSESSMENT_ID = "a1b2c3d4-SECRET-ASSESSMENT";
    public static final LocalDate FROM = LocalDate.parse("2026-11-02");
    public static final LocalDate TO = LocalDate.parse("2026-11-22");

    private InsightsFixtures() {
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /**
     * 30 jornadas de 3 operarios: W1 con una excedencia y 5 jornadas sobre la advertencia, tendencia
     * al alza, 2 jornadas con cobertura incompleta; ahora W2 CRITICAL (reprogramar) y W4 HIGH (rotar
     * con W3).
     */
    public static AnalyticsSummary rich() {
        return new AnalyticsSummary(ORG, FROM, TO, "America/Lima", Instant.parse("2026-11-22T15:00:00Z"),
                new Totals(30, 3, 7, 3, d("10.00"), 1, d("3.33"), 2, d("0.90")),
                List.of(new WorkerStats(W1, 10, d("9800"), d("12360"), d("1.030000"), 5, 2, 1),
                        new WorkerStats(W2, 10, d("7000"), d("10900"), d("0.908333"), 2, 1, 0),
                        new WorkerStats(W3, 10, d("4000"), d("6000"), d("0.500000"), 0, 0, 0)),
                List.of(new WeekStats("2026-W45", LocalDate.parse("2026-11-02"), 10, d("6000"), d("9000"), 0, 0),
                        new WeekStats("2026-W46", LocalDate.parse("2026-11-09"), 10, d("7000"), d("10900"), 1, 0),
                        new WeekStats("2026-W47", LocalDate.parse("2026-11-16"), 10, d("7800"), d("12360"), 2, 1)),
                List.of(new PositionStats(P1, POSITION_NAME, 10, d("9800"), d("12360")),
                        new PositionStats(P2, null, 20, d("5500"), d("10900"))),
                List.of(new WarningWorker(W1, 5, Instant.parse("2026-11-21T21:00:00Z")),
                        new WarningWorker(W2, 2, Instant.parse("2026-11-20T21:00:00Z"))),
                List.of(new ProjectedCritical(ASSESSMENT_ID, W2, SESSION_ID, CriticalityLevel.CRITICAL,
                                d("10900"), d("21891"), d("12000"), d("1.824250"),
                                Instant.parse("2026-11-22T16:47:00Z"), RecommendationType.RESCHEDULE_TASK, null),
                        new ProjectedCritical(ASSESSMENT_ID + "-2", W4, SESSION_ID + "-2", CriticalityLevel.HIGH,
                                d("7200"), d("14400"), d("12000"), d("1.200000"), null,
                                RecommendationType.ROTATION, W3)),
                new Recommendations(1, 1, d("5000")));
    }

    /** Periodo sin jornadas cerradas ni proyecciones. */
    public static AnalyticsSummary empty() {
        return new AnalyticsSummary(ORG, FROM, TO, "America/Lima", Instant.parse("2026-11-22T15:00:00Z"),
                new Totals(0, 0, 0, 0, null, 0, null, 0, d("0.90")),
                List.of(), List.of(), List.of(), List.of(), List.of(), new Recommendations(0, 0, BigDecimal.ZERO));
    }
}
