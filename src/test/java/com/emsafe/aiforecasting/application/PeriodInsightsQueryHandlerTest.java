package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.AnalyticsSummaryQueryHandlerTest.FakeHistory;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.WorkerNames;
import com.emsafe.aiforecasting.domain.ports.PeriodInsightsPort;
import com.emsafe.aiforecasting.domain.services.ExposureAnalyticsCalculator;
import com.emsafe.aiforecasting.domain.services.InsightsFallbackPolicy;
import com.emsafe.shared.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.emsafe.aiforecasting.application.ForecastingTestContext.ORG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reloj del contexto: 2026-11-10T12:00Z. */
class PeriodInsightsQueryHandlerTest {

    private static final LocalDate FROM = LocalDate.parse("2026-11-02");
    private static final LocalDate TO = LocalDate.parse("2026-11-08");

    private final ForecastingTestContext ctx = new ForecastingTestContext();
    private final FakeHistory history = new FakeHistory();
    private final AnalyticsSummaryQueryHandler summaries = new AnalyticsSummaryQueryHandler(history,
            org -> Map.of(), ctx.assessments,
            new OpenSessionAssessments(ctx.assessments, ctx.snapshots, history, (org, worker) -> Optional.empty(),
                    ctx.clock),
            new ExposureAnalyticsCalculator(new BigDecimal("0.90")), ctx.clock);
    private final InsightsFallbackPolicy fallback = new InsightsFallbackPolicy();

    /** Puerto que cuenta las llamadas y guarda los resúmenes recibidos. */
    private final class CountingPort implements PeriodInsightsPort {
        final List<AnalyticsSummary> received = new ArrayList<>();
        final List<WorkerNames> names = new ArrayList<>();
        RuntimeException failure;

        @Override
        public PeriodInsights analyze(AnalyticsSummary summary, WorkerNames workerNames) {
            received.add(summary);
            names.add(workerNames);
            if (failure != null) throw failure;
            return fallback.analyze(summary, workerNames, ctx.clock.instant());
        }
    }

    /** Directorio de nombres: registra qué IDs se piden; puede fallar. */
    private final List<java.util.Set<Long>> nameRequests = new ArrayList<>();
    private RuntimeException namesFailure;
    private final com.emsafe.aiforecasting.domain.ports.WorkerNamePort directory = (org, ids) -> {
        nameRequests.add(ids);
        if (namesFailure != null) throw namesFailure;
        return ids.contains(101L) ? Map.of(101L, "Ana Torres") : Map.of();
    };

    private final CountingPort port = new CountingPort();
    private final PeriodInsightsQueryHandler handler =
            new PeriodInsightsQueryHandler(summaries, port, directory, fallback, ctx.clock, Duration.ofMinutes(10),
                    Duration.ofMinutes(1));

    @Test
    void reusesTheSummaryOfThePeriod() {
        PeriodInsights i = handler.analyze(ORG, FROM, TO, "UTC", false);

        assertThat(port.received).singleElement().satisfies(s -> {
            assertThat(s.organizationId()).isEqualTo(ORG);
            assertThat(s.from()).isEqualTo(FROM);
            assertThat(s.to()).isEqualTo(TO);
            assertThat(s.zone()).isEqualTo("UTC");
        });
        assertThat(history.calls).hasSize(1);
        assertThat(i.from()).isEqualTo(FROM);
        assertThat(i.zone()).isEqualTo("UTC");
    }

    @Test
    void repeatedCallsWithinTenMinutesAreServedFromTheCache() {
        PeriodInsights first = handler.analyze(ORG, FROM, TO, "UTC", false);
        ctx.clock.advance(Duration.ofMinutes(9));
        PeriodInsights second = handler.analyze(ORG, FROM, TO, "UTC", false);

        assertThat(second).isSameAs(first);
        assertThat(port.received).hasSize(1);
        assertThat(history.calls).hasSize(1);

        ctx.clock.advance(Duration.ofMinutes(1));
        PeriodInsights expired = handler.analyze(ORG, FROM, TO, "UTC", false);
        assertThat(expired).isNotSameAs(first);
        assertThat(expired.generatedAt()).isAfter(first.generatedAt());
        assertThat(port.received).hasSize(2);
    }

    @Test
    void refreshForcesANewAnalysisAndRenewsTheCache() {
        PeriodInsights first = handler.analyze(ORG, FROM, TO, "UTC", false);
        ctx.clock.advance(Duration.ofMinutes(1));

        PeriodInsights refreshed = handler.analyze(ORG, FROM, TO, "UTC", true);
        PeriodInsights afterwards = handler.analyze(ORG, FROM, TO, "UTC", false);

        assertThat(refreshed).isNotSameAs(first);
        assertThat(afterwards).isSameAs(refreshed);
        assertThat(port.received).hasSize(2);
    }

    @Test
    void theKeyIsOrganizationResolvedPeriodAndZone() {
        handler.analyze(ORG, FROM, TO, "UTC", false);
        handler.analyze(ORG, FROM, TO, "America/Lima", false);
        handler.analyze(ORG, FROM, TO.plusDays(1), "UTC", false);
        handler.analyze(ORG + 1, FROM, TO, "UTC", false);
        assertThat(port.received).hasSize(4);

        // sin from/to: el periodo resuelto (28 días hasta hoy en Lima) es el mismo; zona vacía = Lima
        handler.analyze(ORG, null, null, null, false);
        handler.analyze(ORG, LocalDate.parse("2026-10-14"), LocalDate.parse("2026-11-10"), "America/Lima", false);
        assertThat(port.received).hasSize(5);
    }

    @Test
    void invalidPeriodIs400BeforeAnySummary() {
        assertThatThrownBy(() -> handler.analyze(ORG, TO, FROM, null, false)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> handler.analyze(ORG, FROM, TO, "Mars/Olympus", false))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> handler.analyze(ORG, LocalDate.parse("2025-01-01"), TO, null, false))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> handler.analyze(null, FROM, TO, null, false)).isInstanceOf(BadRequestException.class);
        assertThat(history.calls).isEmpty();
        assertThat(port.received).isEmpty();
    }

    @Test
    void aFailingPortFallsBackToTheDeterministicRules() {
        port.failure = new IllegalStateException("boom");

        PeriodInsights i = handler.analyze(ORG, FROM, TO, "UTC", false);

        assertThat(i.generatedBy()).isEqualTo(RedactionSource.FALLBACK);
        assertThat(i).isEqualTo(fallback.analyze(port.received.get(0), port.names.get(0), ctx.clock.instant()));
    }

    @Test
    void withTheQuotaExceededTheFallbackIsCachedForOnlyOneMinute() {
        port.failure = new com.emsafe.aiforecasting.domain.model.LlmQuotaExceededException("gemini-3.5-flash-lite");

        PeriodInsights first = handler.analyze(ORG, FROM, TO, "UTC", false);
        assertThat(first.generatedBy()).isEqualTo(RedactionSource.FALLBACK);

        ctx.clock.advance(Duration.ofSeconds(59));
        assertThat(handler.analyze(ORG, FROM, TO, "UTC", false)).isSameAs(first);
        assertThat(port.received).hasSize(1);

        // pasado el minuto se vuelve a probar el LLM; si ya responde, se guarda los 10 min normales
        ctx.clock.advance(Duration.ofSeconds(1));
        port.failure = null;
        PeriodInsights retried = handler.analyze(ORG, FROM, TO, "UTC", false);
        assertThat(retried).isNotSameAs(first);
        assertThat(port.received).hasSize(2);
        ctx.clock.advance(Duration.ofMinutes(9));
        assertThat(handler.analyze(ORG, FROM, TO, "UTC", false)).isSameAs(retried);
    }

    @Test
    void otherFailuresKeepTheTenMinuteCache() {
        port.failure = new IllegalStateException("boom");

        PeriodInsights first = handler.analyze(ORG, FROM, TO, "UTC", false);
        ctx.clock.advance(Duration.ofMinutes(9));

        assertThat(handler.analyze(ORG, FROM, TO, "UTC", false)).isSameAs(first);
        assertThat(port.received).hasSize(1);
    }

    @Test
    void workerNamesComeFromTheDirectoryForTheWorkersOfTheSummary() {
        ctx.seedHighScenario();
        ctx.trigger.handle(ForecastingTestContext.warningCmd("w101", 101L, "7200"));

        PeriodInsights i = handler.analyze(ORG, null, null, null, false);

        // 101 proyectado HIGH con rotación hacia 200: se piden ambos
        assertThat(nameRequests).singleElement().satisfies(ids -> assertThat(ids).contains(101L, 200L));
        assertThat(port.names.get(0).label(101L)).isEqualTo("Ana Torres");
        assertThat(port.names.get(0).label(200L)).isEqualTo("operario #200");
        assertThat(i.recommendations().get(0).action()).isEqualTo("Rotar a Ana Torres con el operario #200 en lo que "
                + "resta del turno.");
        assertThat(i.recommendations().get(0).workerIds()).containsExactly(101L, 200L);
    }

    @Test
    void ifTheDirectoryFailsTheTextsUseIds() {
        ctx.seedHighScenario();
        ctx.trigger.handle(ForecastingTestContext.warningCmd("w101", 101L, "7200"));
        namesFailure = new IllegalStateException("IAM down");

        PeriodInsights i = handler.analyze(ORG, null, null, null, false);

        assertThat(i.recommendations().get(0).action()).startsWith("Rotar al operario #101 con el operario #200");
    }

    @Test
    void anEmptyPeriodDoesNotAskForNames() {
        handler.analyze(ORG, FROM, TO, "UTC", false);

        assertThat(nameRequests).isEmpty();
    }

    @Test
    void itIsReadOnly() {
        ctx.seedHighScenario();
        ctx.trigger.handle(ForecastingTestContext.warningCmd("w101", 101L, "7200"));
        List<String> before = state();
        int outbox = ctx.outbox.all().size();

        handler.analyze(ORG, null, null, null, true);

        assertThat(state()).isNotEmpty().isEqualTo(before);
        assertThat(ctx.outbox.all()).hasSize(outbox);
    }

    private List<String> state() {
        return ctx.assessments.all().stream()
                .map(a -> a.getId() + "|" + a.getStatus() + "|" + a.getCriticality() + "|" + a.getRecommendation())
                .toList();
    }
}
