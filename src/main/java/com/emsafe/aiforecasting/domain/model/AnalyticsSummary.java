package com.emsafe.aiforecasting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Resumen analítico de una organización en un periodo. El histórico ({@code totals},
 * {@code perWorker}, {@code weeklyTrend}, {@code topPositions}, {@code workersAboveWarning}) es
 * dato medido por Personal Dosimetry; solo {@code projectedCriticalWorkers} es proyección.
 * Índices en uT·s; los porcentajes son {@code null} si no hay jornadas en el periodo.
 */
public record AnalyticsSummary(Long organizationId,
                               LocalDate from,
                               LocalDate to,
                               String zone,
                               Instant generatedAt,
                               Totals totals,
                               List<WorkerStats> perWorker,
                               List<WeekStats> weeklyTrend,
                               List<PositionStats> topPositions,
                               List<WarningWorker> workersAboveWarning,
                               List<ProjectedCritical> projectedCriticalWorkers,
                               Recommendations recommendations) {

    public AnalyticsSummary {
        perWorker = List.copyOf(perWorker);
        weeklyTrend = List.copyOf(weeklyTrend);
        topPositions = List.copyOf(topPositions);
        workersAboveWarning = List.copyOf(workersAboveWarning);
        projectedCriticalWorkers = List.copyOf(projectedCriticalWorkers);
    }

    public record Totals(int closedSessions,
                         int workers,
                         int sessionsAboveWarning,
                         int sessionsNearLimit,
                         BigDecimal sessionsNearLimitPct,
                         int sessionsExceeded,
                         BigDecimal sessionsExceededPct,
                         int incompleteCoverageSessions,
                         BigDecimal nearLimitRatio) {
    }

    public record WorkerStats(Long workerId,
                              int sessions,
                              BigDecimal avgFinalIndex,
                              BigDecimal maxFinalIndex,
                              BigDecimal maxRatio,
                              int sessionsAboveWarning,
                              int sessionsNearLimit,
                              int sessionsExceeded) {
    }

    /** Semana ISO en la zona pedida. Sin jornadas, los promedios y máximos son {@code null}. */
    public record WeekStats(String isoWeek,
                            LocalDate weekStart,
                            int sessions,
                            BigDecimal avgFinalIndex,
                            BigDecimal maxFinalIndex,
                            int sessionsNearLimit,
                            int sessionsExceeded) {
    }

    /** {@code positionName} es {@code null} si Plant Configuration no lo conoce o no respondió. */
    public record PositionStats(Long positionId,
                                String positionName,
                                int sessions,
                                BigDecimal avgFinalIndex,
                                BigDecimal maxFinalIndex) {
    }

    public record WarningWorker(Long workerId, int sessionsAboveWarning, Instant lastAt) {
    }

    /** Evaluación activa HIGH o CRITICAL de una jornada que sigue abierta. */
    public record ProjectedCritical(String assessmentId,
                                    Long workerId,
                                    String sessionId,
                                    CriticalityLevel criticality,
                                    BigDecimal currentIndex,
                                    BigDecimal projectedIndex,
                                    BigDecimal limitIndex,
                                    BigDecimal projectedRatio,
                                    Instant estimatedExceedanceAt,
                                    RecommendationType recommendationType,
                                    Long targetWorkerId) {
    }

    /**
     * Recomendaciones emitidas en el periodo: por jornada cuenta solo la evaluación más reciente,
     * para que una reevaluación no se cuente dos veces.
     */
    public record Recommendations(int rotation, int rescheduleTask, BigDecimal estimatedBenefitIndex) {
    }
}
