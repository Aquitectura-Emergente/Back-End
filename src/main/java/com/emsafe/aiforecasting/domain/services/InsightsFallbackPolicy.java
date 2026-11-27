package com.emsafe.aiforecasting.domain.services;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.PositionStats;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.ProjectedCritical;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.Totals;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WeekStats;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WorkerStats;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.Decimals;
import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.Finding;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.FindingLevel;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.InsightPriority;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.PositionRef;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.Recommendation;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.WorkerNames;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;

/**
 * "Análisis con IA" con reglas deterministas a partir del {@link AnalyticsSummary}, con la misma
 * estructura que el del LLM. Se usa con el LLM deshabilitado, sin clave, ante error o timeout, o si
 * la respuesta no es válida. Solo lee el resumen: no recalcula nada.
 *
 * <ul>
 *   <li>Hallazgos (de mayor a menor nivel): excedencias del periodo y proyecciones CRITICAL
 *       (CRITICO); proyecciones HIGH, jornadas cerca del límite, operarios con
 *       {@value #RECURRENT_WARNING_SESSIONS}+ jornadas sobre la advertencia y tendencia semanal al
 *       alza de más del {@value #TREND_CHANGE_PCT} % (ATENCION); puesto con mayor índice y tendencia
 *       estable o a la baja (INFO).</li>
 *   <li>Recomendaciones: la acción del motor para cada proyección HIGH/CRITICAL y la revisión de las
 *       excedencias (ALTA); revisar el puesto con mayor índice, rotación y pausas para los operarios
 *       recurrentes y revisar la carga si la tendencia sube (MEDIA); capacitación si hay cobertura
 *       incompleta, o mantener el monitoreo si no hay nada que atender (BAJA).</li>
 *   <li>Calidad de datos: cobertura incompleta y menos de {@value #FEW_SESSIONS} jornadas cerradas.</li>
 * </ul>
 */
public class InsightsFallbackPolicy {

    static final int RECURRENT_WARNING_SESSIONS = 3;
    static final int TREND_CHANGE_PCT = 10;
    static final int FEW_SESSIONS = 10;
    static final int MAX_PROJECTED_ACTIONS = 4;
    private static final int MAX_LISTED_WORKERS = 8;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public PeriodInsights analyze(AnalyticsSummary s, Instant generatedAt) {
        return analyze(s, WorkerNames.none(), generatedAt);
    }

    /** Los operarios se nombran con {@code names} (nombre o "operario #id"). */
    public PeriodInsights analyze(AnalyticsSummary s, WorkerNames names, Instant generatedAt) {
        requirePresent(s, "summary");
        requirePresent(generatedAt, "generatedAt");
        WorkerNames n = names == null ? WorkerNames.none() : names;
        return new PeriodInsights(s.organizationId(), s.from(), s.to(), s.zone(), RedactionSource.FALLBACK, null,
                generatedAt, fit(summary(s), PeriodInsights.MAX_SUMMARY_LENGTH), findings(s, n),
                recommendations(s, n), fit(dataQuality(s.totals()), PeriodInsights.MAX_DATA_QUALITY_LENGTH));
    }

    private static String fit(String text, int max) {
        return PeriodInsights.fit(text, max);
    }

    private static Finding finding(String text, FindingLevel level) {
        return new Finding(fit(text, PeriodInsights.MAX_FINDING_LENGTH), level);
    }

    private static Recommendation recommendation(String action, String reason, InsightPriority priority,
                                                 List<Long> workerIds, List<PositionRef> positions) {
        return new Recommendation(fit(action, PeriodInsights.MAX_ACTION_LENGTH),
                fit(reason, PeriodInsights.MAX_REASON_LENGTH), priority, workerIds, positions);
    }

    // ─── Resumen ─────────────────────────────────────────────────────────────

    private static String summary(AnalyticsSummary s) {
        Totals t = s.totals();
        StringBuilder text = new StringBuilder();
        if (t.closedSessions() == 0) {
            text.append("Entre el ").append(s.from()).append(" y el ").append(s.to())
                    .append(" no hay jornadas cerradas registradas. ");
        } else {
            text.append("Entre el ").append(s.from()).append(" y el ").append(s.to()).append(" se cerraron ")
                    .append(t.closedSessions()).append(plural(t.closedSessions(), " jornada", " jornadas"))
                    .append(" de ").append(t.workers()).append(plural(t.workers(), " operario", " operarios"))
                    .append(": ").append(t.sessionsNearLimit()).append(" (").append(pct(t.sessionsNearLimitPct()))
                    .append(" %) terminaron a ").append(Decimals.percent(t.nearLimitRatio()))
                    .append(" % del límite o más y ").append(t.sessionsExceeded()).append(" (")
                    .append(pct(t.sessionsExceededPct())).append(" %) lo superaron. ");
        }
        List<ProjectedCritical> projected = s.projectedCriticalWorkers();
        if (projected.isEmpty()) {
            text.append("Ahora no hay operarios con proyección HIGH o CRITICAL en jornadas abiertas.");
        } else {
            long critical = projected.stream().filter(p -> p.criticality() == CriticalityLevel.CRITICAL).count();
            text.append("Ahora hay ").append(projected.size())
                    .append(plural(projected.size(), " operario", " operarios"))
                    .append(" con proyección HIGH o CRITICAL en jornadas abiertas (").append(critical)
                    .append(" CRITICAL).");
        }
        return text.toString();
    }

    // ─── Hallazgos ───────────────────────────────────────────────────────────

    private static List<Finding> findings(AnalyticsSummary s, WorkerNames n) {
        Totals t = s.totals();
        List<Finding> critical = new ArrayList<>();
        List<Finding> attention = new ArrayList<>();
        List<Finding> info = new ArrayList<>();

        if (t.sessionsExceeded() > 0) {
            critical.add(finding(t.sessionsExceeded() + plural(t.sessionsExceeded(), " jornada superó",
                    " jornadas superaron") + " el límite en el periodo (" + pct(t.sessionsExceededPct())
                    + " %): " + workers(n, "", exceededWorkers(s)) + ".", FindingLevel.CRITICO));
        }
        List<Long> projectedCritical = projected(s, CriticalityLevel.CRITICAL);
        if (!projectedCritical.isEmpty()) {
            critical.add(finding("Proyección CRITICAL en la jornada abierta " + workers(n, "de", projectedCritical)
                    + ": se estima superar el límite antes del fin del turno.", FindingLevel.CRITICO));
        }
        List<Long> projectedHigh = projected(s, CriticalityLevel.HIGH);
        if (!projectedHigh.isEmpty()) {
            attention.add(finding("Proyección HIGH en la jornada abierta " + workers(n, "de", projectedHigh)
                    + ": el índice se acerca al límite.", FindingLevel.ATENCION));
        }
        int nearOnly = t.sessionsNearLimit() - t.sessionsExceeded();
        if (nearOnly > 0) {
            attention.add(finding(nearOnly + plural(nearOnly, " jornada terminó", " jornadas terminaron")
                    + " entre el " + Decimals.percent(t.nearLimitRatio()) + " % y el 100 % del límite.",
                    FindingLevel.ATENCION));
        }
        List<WorkerStats> recurrent = recurrentWarning(s);
        if (!recurrent.isEmpty()) {
            attention.add(finding("Jornadas repetidas sobre la advertencia: " + recurrent.stream()
                    .limit(MAX_LISTED_WORKERS)
                    .map(w -> n.label(w.workerId()) + " (" + w.sessionsAboveWarning() + ")")
                    .collect(Collectors.joining(", ")) + ".", FindingLevel.ATENCION));
        }
        Trend trend = trend(s.weeklyTrend());
        if (trend != null) {
            String text = "El índice final promedio semanal pasó de " + index(trend.first().avgFinalIndex())
                    + " uT·s (" + trend.first().isoWeek() + ") a " + index(trend.last().avgFinalIndex())
                    + " uT·s (" + trend.last().isoWeek() + ")";
            if (trend.changePct() > TREND_CHANGE_PCT) {
                attention.add(finding(text + ": tendencia al alza.", FindingLevel.ATENCION));
            } else if (trend.changePct() < -TREND_CHANGE_PCT) {
                info.add(finding(text + ": tendencia a la baja.", FindingLevel.INFO));
            } else {
                info.add(finding(text + ": tendencia estable.", FindingLevel.INFO));
            }
        }
        PositionStats top = topPosition(s);
        if (top != null) {
            info.add(finding(capitalize(position(top)) + " tiene el mayor índice final promedio ("
                    + index(top.avgFinalIndex()) + " uT·s; máximo " + index(top.maxFinalIndex()) + " uT·s en "
                    + top.sessions() + plural(top.sessions(), " jornada", " jornadas") + ").", FindingLevel.INFO));
        }

        List<Finding> all = new ArrayList<>(critical);
        all.addAll(attention);
        all.addAll(info);
        if (all.isEmpty()) {
            all.add(finding("No hay jornadas cerradas en el periodo ni proyecciones HIGH o CRITICAL.",
                    FindingLevel.INFO));
        }
        return all.stream().limit(PeriodInsights.MAX_FINDINGS).toList();
    }

    // ─── Recomendaciones ─────────────────────────────────────────────────────

    private static List<Recommendation> recommendations(AnalyticsSummary s, WorkerNames n) {
        Totals t = s.totals();
        List<Recommendation> out = new ArrayList<>();

        DateTimeFormatter time = TIME.withZone(ZoneId.of(s.zone()));
        for (ProjectedCritical p : s.projectedCriticalWorkers().stream().limit(MAX_PROJECTED_ACTIONS).toList()) {
            String reason = "Proyección " + p.criticality() + ": " + Decimals.percent(p.projectedRatio())
                    + " % del límite al fin del turno"
                    + (p.estimatedExceedanceAt() == null ? "" : "; excedencia estimada a las "
                    + time.format(p.estimatedExceedanceAt()) + " (" + s.zone() + ")") + ".";
            if (p.recommendationType() == RecommendationType.ROTATION && p.targetWorkerId() != null) {
                out.add(recommendation("Rotar " + n.with("a", p.workerId()) + " " + n.with("con", p.targetWorkerId())
                        + " en lo que resta del turno.", reason, InsightPriority.ALTA,
                        List.of(p.workerId(), p.targetWorkerId()), List.of()));
            } else if (p.recommendationType() == RecommendationType.RESCHEDULE_TASK) {
                out.add(recommendation("Reprogramar las tareas restantes " + n.with("de", p.workerId())
                        + " (no hay candidato viable para rotar).", reason, InsightPriority.ALTA,
                        List.of(p.workerId()), List.of()));
            } else {
                out.add(recommendation("Programar una pausa y revisar las tareas restantes "
                        + n.with("de", p.workerId()) + ".", reason, InsightPriority.ALTA, List.of(p.workerId()),
                        List.of()));
            }
        }
        List<Long> exceeded = exceededWorkers(s);
        if (!exceeded.isEmpty()) {
            out.add(recommendation("Revisar " + workers(n, "con", exceeded) + " las tareas, el puesto y el blindaje "
                    + "de las jornadas que superaron el límite.", t.sessionsExceeded()
                    + plural(t.sessionsExceeded(), " jornada", " jornadas") + " por encima del límite en el periodo.",
                    InsightPriority.ALTA, exceeded, List.of()));
        }
        PositionStats top = topPosition(s);
        if (top != null && t.sessionsNearLimit() > 0) {
            out.add(recommendation("Revisar el blindaje, la distancia a la fuente y los tiempos de permanencia "
                    + "en " + position(top) + ".", "Es el puesto con mayor índice final promedio ("
                    + index(top.avgFinalIndex()) + " uT·s) y hubo jornadas cerca del límite.",
                    InsightPriority.MEDIA, List.of(), List.of(new PositionRef(top.positionId(), top.positionName()))));
        }
        List<WorkerStats> recurrent = recurrentWarning(s);
        if (!recurrent.isEmpty()) {
            List<Long> ids = recurrent.stream().map(WorkerStats::workerId).toList();
            out.add(recommendation("Programar rotación periódica y pausas " + workers(n, "para", ids) + ".",
                    (ids.size() == 1 ? "Termina" : "Terminan") + " con frecuencia sobre la advertencia ("
                            + RECURRENT_WARNING_SESSIONS
                            + " o más jornadas en el periodo).", InsightPriority.MEDIA, ids, List.of()));
        }
        Trend trend = trend(s.weeklyTrend());
        if (trend != null && trend.changePct() > TREND_CHANGE_PCT) {
            out.add(recommendation("Revisar la planificación de tareas de las próximas semanas.",
                    "El índice final promedio semanal sube más de un " + TREND_CHANGE_PCT + " % en el periodo.",
                    InsightPriority.MEDIA, List.of(), List.of()));
        }
        if (t.incompleteCoverageSessions() > 0) {
            out.add(recommendation("Reforzar la capacitación sobre el uso continuo del dosímetro.",
                    t.incompleteCoverageSessions() + plural(t.incompleteCoverageSessions(), " jornada", " jornadas")
                            + " con cobertura incompleta: el índice real pudo ser mayor.",
                    InsightPriority.BAJA, List.of(), List.of()));
        }
        if (out.isEmpty()) {
            out.add(recommendation("Mantener el monitoreo y la capacitación periódica.",
                    "No hay jornadas cerca del límite ni proyecciones HIGH o CRITICAL.", InsightPriority.BAJA,
                    List.of(), List.of()));
        }
        return out.stream().limit(PeriodInsights.MAX_RECOMMENDATIONS).toList();
    }

    // ─── Calidad de datos ────────────────────────────────────────────────────

    private static String dataQuality(Totals t) {
        if (t.closedSessions() == 0) {
            return "Sin jornadas cerradas en el periodo: no hay base histórica para el análisis.";
        }
        List<String> notes = new ArrayList<>();
        if (t.incompleteCoverageSessions() > 0) {
            notes.add(t.incompleteCoverageSessions() + " de " + t.closedSessions()
                    + plural(t.closedSessions(), " jornada", " jornadas")
                    + plural(t.incompleteCoverageSessions(), " tiene", " tienen")
                    + " cobertura incompleta; su índice puede estar subestimado.");
        }
        if (t.closedSessions() < FEW_SESSIONS) {
            notes.add("Pocos datos: solo " + t.closedSessions()
                    + plural(t.closedSessions(), " jornada cerrada", " jornadas cerradas")
                    + "; las tendencias son poco fiables.");
        }
        if (notes.isEmpty()) {
            return "Cobertura completa en las " + t.closedSessions() + " jornadas cerradas del periodo.";
        }
        return String.join(" ", notes);
    }

    // ─── Auxiliares ──────────────────────────────────────────────────────────

    private record Trend(WeekStats first, WeekStats last, double changePct) {
    }

    /** Primera y última semana con jornadas; {@code null} si hay menos de dos. */
    private static Trend trend(List<WeekStats> weeks) {
        List<WeekStats> withData = weeks.stream()
                .filter(w -> w.sessions() > 0 && w.avgFinalIndex() != null).toList();
        if (withData.size() < 2) {
            return null;
        }
        WeekStats first = withData.get(0);
        WeekStats last = withData.get(withData.size() - 1);
        if (first.avgFinalIndex().signum() == 0) {
            return null;
        }
        double change = last.avgFinalIndex().subtract(first.avgFinalIndex())
                .divide(first.avgFinalIndex(), 6, RoundingMode.HALF_UP).doubleValue() * 100;
        return new Trend(first, last, change);
    }

    private static PositionStats topPosition(AnalyticsSummary s) {
        return s.topPositions().stream().filter(p -> p.avgFinalIndex() != null)
                .max(Comparator.comparing(PositionStats::avgFinalIndex)).orElse(null);
    }

    private static List<Long> exceededWorkers(AnalyticsSummary s) {
        return s.perWorker().stream().filter(w -> w.sessionsExceeded() > 0).map(WorkerStats::workerId).toList();
    }

    private static List<WorkerStats> recurrentWarning(AnalyticsSummary s) {
        return s.perWorker().stream().filter(w -> w.sessionsAboveWarning() >= RECURRENT_WARNING_SESSIONS)
                .sorted(Comparator.comparingInt(WorkerStats::sessionsAboveWarning).reversed()).toList();
    }

    private static List<Long> projected(AnalyticsSummary s, CriticalityLevel level) {
        Set<Long> ids = new LinkedHashSet<>();
        s.projectedCriticalWorkers().stream().filter(p -> p.criticality() == level)
                .forEach(p -> ids.add(p.workerId()));
        return List.copyOf(ids);
    }

    /** "de Lucía Mendoza y Carlos Rojas", "con el operario #12"…; más de {@value #MAX_LISTED_WORKERS}, "y N más". */
    private static String workers(WorkerNames n, String preposition, List<Long> ids) {
        String listed = n.list(preposition, ids.stream().limit(MAX_LISTED_WORKERS).toList());
        return ids.size() > MAX_LISTED_WORKERS ? listed + " y " + (ids.size() - MAX_LISTED_WORKERS) + " más" : listed;
    }

    private static String position(PositionStats p) {
        return p.positionName() == null ? "el puesto " + p.positionId() : "el puesto " + p.positionName();
    }

    private static String index(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plural(int n, String one, String many) {
        return n == 1 ? one : many;
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
