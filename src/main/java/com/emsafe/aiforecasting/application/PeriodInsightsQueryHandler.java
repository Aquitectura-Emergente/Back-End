package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.AnalyticsSummaryQueryHandler.Period;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.WorkerNames;
import com.emsafe.aiforecasting.domain.ports.PeriodInsightsPort;
import com.emsafe.aiforecasting.domain.ports.WorkerNamePort;
import com.emsafe.aiforecasting.domain.services.InsightsFallbackPolicy;
import com.emsafe.shared.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Análisis con IA" de un periodo. La autorización la hace el controlador con
 * {@link ForecastingAccessPolicy} antes de llamar aquí; el periodo se valida igual que en el
 * resumen (400).
 *
 * <ul>
 *   <li>Reutiliza el {@link AnalyticsSummary} de {@link AnalyticsSummaryQueryHandler}: no recalcula
 *       nada y la llamada al LLM queda fuera de la transacción de lectura.</li>
 *   <li>Es consultivo y de solo lectura: no cambia criticidades, evaluaciones ni eventos.</li>
 *   <li>Caché en memoria por (organización, periodo resuelto, zona) durante {@code ttl} (10 min), para
 *       no repetir llamadas al LLM; {@code refresh=true} la ignora y la renueva. Se guarda también el
 *       fallback (si el LLM está caído, evita esperar el timeout en cada clic).</li>
 *   <li>Los textos nombran a los operarios con su nombre de IAM ({@link WorkerNamePort}); sin nombre,
 *       o si IAM falla, "operario #id". Los nombres no se envían al LLM.</li>
 *   <li>Si el puerto lanzara una excepción, se usa {@link InsightsFallbackPolicy}.</li>
 * </ul>
 */
public class PeriodInsightsQueryHandler {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    static final int MAX_CACHE_ENTRIES = 500;
    private static final Logger log = LoggerFactory.getLogger(PeriodInsightsQueryHandler.class);

    private final AnalyticsSummaryQueryHandler summaries;
    private final PeriodInsightsPort insights;
    private final WorkerNamePort workerNames;
    private final InsightsFallbackPolicy fallback;
    private final Clock clock;
    private final Duration ttl;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    public PeriodInsightsQueryHandler(AnalyticsSummaryQueryHandler summaries, PeriodInsightsPort insights,
                                      WorkerNamePort workerNames, InsightsFallbackPolicy fallback, Clock clock,
                                      Duration ttl) {
        this.summaries = summaries;
        this.insights = insights;
        this.workerNames = workerNames;
        this.fallback = fallback;
        this.clock = clock;
        this.ttl = ttl;
    }

    private record Key(Long organizationId, LocalDate from, LocalDate to, ZoneId zone) {
    }

    private record Entry(PeriodInsights insights, Instant expiresAt) {
    }

    public PeriodInsights analyze(Long organizationId, LocalDate from, LocalDate to, String zone, boolean refresh) {
        if (organizationId == null) {
            throw new BadRequestException("organizationId is required");
        }
        Period period = summaries.resolve(from, to, zone);
        Key key = new Key(organizationId, period.from(), period.to(), period.zone());
        Instant now = clock.instant();
        Entry cached = cache.get(key);
        if (!refresh && cached != null && now.isBefore(cached.expiresAt())) {
            return cached.insights();
        }

        AnalyticsSummary summary = summaries.summarize(organizationId, period.from(), period.to(),
                period.zone().getId());
        WorkerNames names = names(organizationId, summary);
        PeriodInsights result;
        try {
            result = insights.analyze(summary, names);
        } catch (RuntimeException e) {
            log.warn("Period insights failed for organization {}; using fallback: {}", organizationId, e.toString());
            result = fallback.analyze(summary, names, clock.instant());
        }
        store(key, result, clock.instant());
        return result;
    }

    private WorkerNames names(Long organizationId, AnalyticsSummary s) {
        Set<Long> ids = new LinkedHashSet<>();
        s.perWorker().forEach(w -> ids.add(w.workerId()));
        s.workersAboveWarning().forEach(w -> ids.add(w.workerId()));
        s.projectedCriticalWorkers().forEach(p -> {
            ids.add(p.workerId());
            if (p.targetWorkerId() != null) ids.add(p.targetWorkerId());
        });
        ids.remove(null);
        if (ids.isEmpty()) {
            return WorkerNames.none();
        }
        try {
            return new WorkerNames(workerNames.namesOf(organizationId, ids));
        } catch (RuntimeException e) {
            log.warn("Worker names unavailable for organization {}; using ids: {}", organizationId, e.toString());
            return WorkerNames.none();
        }
    }

    private void store(Key key, PeriodInsights result, Instant now) {
        cache.values().removeIf(e -> !now.isBefore(e.expiresAt()));
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new Entry(result, now.plus(ttl)));
    }
}
