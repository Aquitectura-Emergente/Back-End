package com.emsafe.aiforecasting.interfaces.rest.dto;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.ExposureMethod;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Representación REST del resumen analítico ({@code GET /analytics/organizations/{id}/summary}). */
public final class AnalyticsResources {

    private AnalyticsResources() {
    }

    @Schema(description = "Resumen analítico de una organización. El histórico es dato medido por Personal "
            + "Dosimetry (isProjection = false); solo projectedCriticalWorkers es proyección.")
    public record Summary(
            Long organizationId,
            @Schema(description = "Primer día del periodo (inclusivo), en la zona pedida") LocalDate from,
            @Schema(description = "Último día del periodo (inclusivo), en la zona pedida") LocalDate to,
            @Schema(example = "America/Lima") String zone,
            @Schema(example = "uT*s") String unit,
            @Schema(example = "MAGNETIC_TIME_INDEX_V1") String methodId,
            @Schema(description = "false: el histórico es dato medido") boolean isProjection,
            Instant generatedAt,
            Totals totals,
            List<WorkerStats> perWorker,
            List<WeekStats> weeklyTrend,
            @Schema(description = "Hasta 10 puestos, por índice final promedio descendente") List<PositionStats> topPositions,
            List<WarningWorker> workersAboveWarning,
            @Schema(description = "Foto actual: evaluaciones activas HIGH/CRITICAL de jornadas aún abiertas")
            List<ProjectedCritical> projectedCriticalWorkers,
            Recommendations recommendations) {

        public static Summary from(AnalyticsSummary s) {
            AnalyticsSummary.Totals t = s.totals();
            return new Summary(s.organizationId(), s.from(), s.to(), s.zone(),
                    ExposureMethod.MICROTESLA_SECONDS, ExposureMethod.MAGNETIC_TIME_INDEX_V1, false, s.generatedAt(),
                    new Totals(t.closedSessions(), t.workers(), t.sessionsAboveWarning(), t.sessionsNearLimit(),
                            t.sessionsNearLimitPct(), t.sessionsExceeded(), t.sessionsExceededPct(),
                            t.incompleteCoverageSessions(), t.nearLimitRatio()),
                    s.perWorker().stream().map(w -> new WorkerStats(w.workerId(), w.sessions(), w.avgFinalIndex(),
                            w.maxFinalIndex(), w.maxRatio(), w.sessionsAboveWarning(), w.sessionsNearLimit(),
                            w.sessionsExceeded())).toList(),
                    s.weeklyTrend().stream().map(w -> new WeekStats(w.isoWeek(), w.weekStart(), w.sessions(),
                            w.avgFinalIndex(), w.maxFinalIndex(), w.sessionsNearLimit(), w.sessionsExceeded()))
                            .toList(),
                    s.topPositions().stream().map(p -> new PositionStats(p.positionId(), p.positionName(),
                            p.sessions(), p.avgFinalIndex(), p.maxFinalIndex())).toList(),
                    s.workersAboveWarning().stream().map(w -> new WarningWorker(w.workerId(),
                            w.sessionsAboveWarning(), w.lastAt())).toList(),
                    s.projectedCriticalWorkers().stream().map(c -> new ProjectedCritical(c.assessmentId(),
                            c.workerId(), c.sessionId(), c.criticality().name(), c.currentIndex(),
                            c.projectedIndex(), c.limitIndex(), c.projectedRatio(), c.estimatedExceedanceAt(),
                            c.recommendationType() == null ? null : c.recommendationType().name(),
                            c.targetWorkerId(), true)).toList(),
                    new Recommendations(s.recommendations().rotation(), s.recommendations().rescheduleTask(),
                            s.recommendations().estimatedBenefitIndex()));
        }
    }

    public record Totals(
            int closedSessions,
            int workers,
            int sessionsAboveWarning,
            int sessionsNearLimit,
            @Schema(description = "Porcentaje con 2 decimales; null si no hay jornadas") BigDecimal sessionsNearLimitPct,
            int sessionsExceeded,
            @Schema(description = "Porcentaje con 2 decimales; null si no hay jornadas") BigDecimal sessionsExceededPct,
            int incompleteCoverageSessions,
            @Schema(description = "Umbral de 'cerca del límite' (índice final / límite)", example = "0.90")
            BigDecimal nearLimitRatio) {
    }

    public record WorkerStats(
            Long workerId,
            int sessions,
            BigDecimal avgFinalIndex,
            BigDecimal maxFinalIndex,
            @Schema(description = "Mayor índice final / límite del operario") BigDecimal maxRatio,
            int sessionsAboveWarning,
            int sessionsNearLimit,
            int sessionsExceeded) {
    }

    public record WeekStats(
            @Schema(example = "2026-W47") String isoWeek,
            @Schema(description = "Lunes de la semana") LocalDate weekStart,
            int sessions,
            @Schema(description = "null si la semana no tiene jornadas") BigDecimal avgFinalIndex,
            @Schema(description = "null si la semana no tiene jornadas") BigDecimal maxFinalIndex,
            int sessionsNearLimit,
            int sessionsExceeded) {
    }

    public record PositionStats(
            Long positionId,
            @Schema(description = "null si Plant Configuration no lo conoce o no respondió") String positionName,
            int sessions,
            BigDecimal avgFinalIndex,
            BigDecimal maxFinalIndex) {
    }

    public record WarningWorker(Long workerId, int sessionsAboveWarning,
                                @Schema(description = "Fin de la última jornada sobre la advertencia") Instant lastAt) {
    }

    public record ProjectedCritical(
            String assessmentId,
            Long workerId,
            String sessionId,
            @Schema(allowableValues = {"HIGH", "CRITICAL"}) String criticality,
            BigDecimal currentIndex,
            BigDecimal projectedIndex,
            BigDecimal limitIndex,
            BigDecimal projectedRatio,
            Instant estimatedExceedanceAt,
            @Schema(allowableValues = {"ROTATION", "RESCHEDULE_TASK"}) String recommendationType,
            Long targetWorkerId,
            @Schema(description = "Siempre true") boolean isProjection) {
    }

    public record Recommendations(
            int rotation,
            int rescheduleTask,
            @Schema(description = "Suma de estimatedBenefitIndex, en uT*s") BigDecimal estimatedBenefitIndex) {
    }
}
