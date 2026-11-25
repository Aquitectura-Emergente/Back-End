package com.emsafe.aiforecasting.domain.services;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.PositionStats;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.ProjectedCritical;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.Recommendations;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.Totals;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WarningWorker;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WeekStats;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WorkerStats;
import com.emsafe.aiforecasting.domain.model.AssessmentPriority;
import com.emsafe.aiforecasting.domain.model.ClosedExposureSession;
import com.emsafe.aiforecasting.domain.model.Decimals;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Calcula el resumen analítico a partir de las jornadas cerradas (dato medido) y de las
 * evaluaciones de este contexto (proyección). Es determinista y no consulta nada: recibe todo.
 *
 * <ul>
 *   <li><b>Cerca del límite:</b> índice final ≥ {@code nearLimitRatio × límite} (incluye las
 *       excedidas).</li>
 *   <li><b>Sobre la advertencia / excedida:</b> las banderas de Personal Dosimetry.</li>
 *   <li><b>Semana:</b> semana ISO de {@code endedAt} en la zona pedida. Se listan todas las semanas
 *       que toca el periodo, también las vacías, para que la tendencia no tenga huecos.</li>
 * </ul>
 */
public class ExposureAnalyticsCalculator {

    static final int TOP_POSITIONS = 10;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BigDecimal nearLimitRatio;

    public ExposureAnalyticsCalculator(BigDecimal nearLimitRatio) {
        if (nearLimitRatio == null || nearLimitRatio.signum() <= 0 || nearLimitRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("near-limit-ratio must be in (0, 1], was " + nearLimitRatio);
        }
        this.nearLimitRatio = nearLimitRatio;
    }

    public BigDecimal nearLimitRatio() {
        return nearLimitRatio;
    }

    /**
     * @param sessions       jornadas cerradas del periodo
     * @param openAssessments evaluaciones activas de jornadas que siguen abiertas (la exclusión de
     *                        las ya cerradas la hace quien llama)
     * @param periodAssessments evaluaciones con {@code assessedAt} en el periodo (para recomendaciones)
     * @param positionNames  nombres de puesto conocidos; puede estar vacío
     */
    public AnalyticsSummary summarize(Long organizationId, LocalDate from, LocalDate to, ZoneId zone,
                                      Instant generatedAt,
                                      List<ClosedExposureSession> sessions,
                                      List<PreventiveAssessment> openAssessments,
                                      List<PreventiveAssessment> periodAssessments,
                                      Map<Long, String> positionNames) {
        return new AnalyticsSummary(organizationId, from, to, zone.getId(), generatedAt,
                totals(sessions),
                perWorker(sessions),
                weeklyTrend(sessions, from, to, zone),
                topPositions(sessions, positionNames),
                workersAboveWarning(sessions),
                projectedCritical(openAssessments),
                recommendations(periodAssessments));
    }

    // ─── Reglas por jornada ──────────────────────────────────────────────────

    boolean nearLimit(ClosedExposureSession s) {
        return s.finalIndex().compareTo(s.limitIndex().multiply(nearLimitRatio)) >= 0;
    }

    private static BigDecimal ratio(ClosedExposureSession s) {
        return Decimals.divide(s.finalIndex(), s.limitIndex());
    }

    // ─── Secciones ───────────────────────────────────────────────────────────

    private Totals totals(List<ClosedExposureSession> sessions) {
        int total = sessions.size();
        int near = count(sessions, this::nearLimit);
        int exceeded = count(sessions, ClosedExposureSession::limitExceeded);
        return new Totals(total,
                (int) sessions.stream().map(ClosedExposureSession::workerId).distinct().count(),
                count(sessions, ClosedExposureSession::warningRaised),
                near, pct(near, total),
                exceeded, pct(exceeded, total),
                count(sessions, s -> !s.coverageComplete()),
                nearLimitRatio);
    }

    private List<WorkerStats> perWorker(List<ClosedExposureSession> sessions) {
        return groupBy(sessions, ClosedExposureSession::workerId).entrySet().stream()
                .map(e -> {
                    List<ClosedExposureSession> own = e.getValue();
                    return new WorkerStats(e.getKey(), own.size(), avg(own), max(own),
                            own.stream().map(ExposureAnalyticsCalculator::ratio).max(Comparator.naturalOrder())
                                    .orElseThrow(),
                            count(own, ClosedExposureSession::warningRaised),
                            count(own, this::nearLimit),
                            count(own, ClosedExposureSession::limitExceeded));
                })
                .sorted(Comparator.comparing(WorkerStats::avgFinalIndex).reversed()
                        .thenComparing(WorkerStats::workerId))
                .toList();
    }

    private List<WeekStats> weeklyTrend(List<ClosedExposureSession> sessions, LocalDate from, LocalDate to,
                                        ZoneId zone) {
        Map<LocalDate, List<ClosedExposureSession>> byWeek = groupBy(sessions,
                s -> weekStart(LocalDate.ofInstant(s.endedAt(), zone)));
        List<WeekStats> weeks = new ArrayList<>();
        for (LocalDate week = weekStart(from); !week.isAfter(to); week = week.plusWeeks(1)) {
            List<ClosedExposureSession> own = byWeek.getOrDefault(week, List.of());
            weeks.add(new WeekStats(isoWeek(week), week, own.size(),
                    own.isEmpty() ? null : avg(own), own.isEmpty() ? null : max(own),
                    count(own, this::nearLimit), count(own, ClosedExposureSession::limitExceeded)));
        }
        return weeks;
    }

    private List<PositionStats> topPositions(List<ClosedExposureSession> sessions, Map<Long, String> names) {
        return groupBy(sessions, ClosedExposureSession::positionId).entrySet().stream()
                .filter(e -> e.getKey() != null)
                .map(e -> new PositionStats(e.getKey(), names.get(e.getKey()), e.getValue().size(),
                        avg(e.getValue()), max(e.getValue())))
                .sorted(Comparator.comparing(PositionStats::avgFinalIndex).reversed()
                        .thenComparing(PositionStats::positionId))
                .limit(TOP_POSITIONS)
                .toList();
    }

    private static List<WarningWorker> workersAboveWarning(List<ClosedExposureSession> sessions) {
        return groupBy(sessions.stream().filter(ClosedExposureSession::warningRaised).toList(),
                ClosedExposureSession::workerId).entrySet().stream()
                .map(e -> new WarningWorker(e.getKey(), e.getValue().size(),
                        e.getValue().stream().map(ClosedExposureSession::endedAt).max(Comparator.naturalOrder())
                                .orElseThrow()))
                .sorted(Comparator.comparing(WarningWorker::sessionsAboveWarning).reversed()
                        .thenComparing(WarningWorker::lastAt, Comparator.reverseOrder())
                        .thenComparing(WarningWorker::workerId))
                .toList();
    }

    private static List<ProjectedCritical> projectedCritical(List<PreventiveAssessment> openAssessments) {
        return openAssessments.stream()
                .filter(a -> a.getCriticality() != null && a.getCriticality().requiresRecommendation())
                .sorted(AssessmentPriority.MOST_URGENT_FIRST)
                .map(a -> {
                    ExposureProjection p = a.getProjection();
                    Optional<RotationRecommendation> r = a.getRecommendation();
                    return new ProjectedCritical(a.getId(), a.getWorkerId(), a.getSessionId(), a.getCriticality(),
                            p.currentIndex(), p.projectedIndexAtShiftEnd(), p.limitIndex(), p.projectedRatio(),
                            p.estimatedExceedanceAt(),
                            r.map(RotationRecommendation::type).orElse(null),
                            r.map(RotationRecommendation::targetWorkerId).orElse(null));
                })
                .toList();
    }

    private static Recommendations recommendations(List<PreventiveAssessment> periodAssessments) {
        Map<String, PreventiveAssessment> latestPerSession = new HashMap<>();
        Comparator<PreventiveAssessment> newer = Comparator.comparing(PreventiveAssessment::getAssessedAt)
                .thenComparing(PreventiveAssessment::getId);
        for (PreventiveAssessment a : periodAssessments) {
            latestPerSession.merge(a.getSessionId(), a, (x, y) -> newer.compare(x, y) >= 0 ? x : y);
        }
        int rotation = 0;
        int reschedule = 0;
        BigDecimal benefit = BigDecimal.ZERO;
        for (PreventiveAssessment a : latestPerSession.values()) {
            Optional<RotationRecommendation> r = a.getRecommendation();
            if (r.isEmpty()) {
                continue;
            }
            if (r.get().type() == RecommendationType.ROTATION) {
                rotation++;
            } else {
                reschedule++;
            }
            if (r.get().estimatedBenefitIndex() != null) {
                benefit = benefit.add(r.get().estimatedBenefitIndex());
            }
        }
        return new Recommendations(rotation, reschedule, Decimals.scaled(benefit));
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────

    private static <K> Map<K, List<ClosedExposureSession>> groupBy(List<ClosedExposureSession> sessions,
                                                                    Function<ClosedExposureSession, K> key) {
        return sessions.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
    }

    private static int count(List<ClosedExposureSession> sessions,
                             java.util.function.Predicate<ClosedExposureSession> condition) {
        return (int) sessions.stream().filter(condition).count();
    }

    private static BigDecimal avg(List<ClosedExposureSession> sessions) {
        BigDecimal sum = sessions.stream().map(ClosedExposureSession::finalIndex)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Decimals.divide(sum, BigDecimal.valueOf(sessions.size()));
    }

    private static BigDecimal max(List<ClosedExposureSession> sessions) {
        return Decimals.scaled(sessions.stream().map(ClosedExposureSession::finalIndex)
                .max(Comparator.naturalOrder()).orElseThrow());
    }

    /** Porcentaje con 2 decimales; {@code null} si no hay jornadas. */
    private static BigDecimal pct(int part, int total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(part).multiply(HUNDRED)
                .divide(BigDecimal.valueOf(total), 2, Decimals.ROUNDING);
    }

    static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    static String isoWeek(LocalDate date) {
        return "%d-W%02d".formatted(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
