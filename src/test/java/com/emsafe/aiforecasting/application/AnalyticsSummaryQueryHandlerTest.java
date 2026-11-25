package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.ClosedExposureSession;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.ports.ClosedSessionHistoryPort;
import com.emsafe.aiforecasting.domain.services.ExposureAnalyticsCalculator;
import com.emsafe.shared.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.emsafe.aiforecasting.application.ForecastingTestContext.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reloj del contexto: 2026-11-10T12:00Z, que en Lima son las 07:00 del mismo día. */
class AnalyticsSummaryQueryHandlerTest {

    private final ForecastingTestContext ctx = new ForecastingTestContext();
    private final FakeHistory history = new FakeHistory();
    private Map<Long, String> positionNames = Map.of();
    private final AnalyticsSummaryQueryHandler handler = new AnalyticsSummaryQueryHandler(history,
            org -> positionNames, ctx.assessments, ctx.snapshots,
            new ExposureAnalyticsCalculator(new BigDecimal("0.90")), ctx.clock);

    /** Histórico en memoria con la semántica del puerto: endedAt en [from, to). Registra cada llamada. */
    static final class FakeHistory implements ClosedSessionHistoryPort {
        final List<ClosedExposureSession> sessions = new ArrayList<>();
        final List<Instant[]> calls = new ArrayList<>();

        @Override
        public List<ClosedExposureSession> closedSessions(Long organizationId, Instant from, Instant to) {
            calls.add(new Instant[]{from, to});
            return sessions.stream()
                    .filter(s -> !s.endedAt().isBefore(from) && s.endedAt().isBefore(to))
                    .toList();
        }

        void closed(String sessionId, long workerId, long positionId, String index, Instant end) {
            sessions.add(new ClosedExposureSession(sessionId, workerId, positionId, new BigDecimal(index),
                    new BigDecimal(LIMIT), false, false, end.minusSeconds(3600), end, true));
        }
    }

    /** 101 → HIGH con rotación hacia 200; 300 → CRITICAL con reprogramación (escenario de la guía). */
    private void seedAssessments() {
        ctx.seedHighScenario();
        ctx.trigger.handle(warningCmd("w101", 101L, "7200"));
        ctx.upsertSnapshot.handle(snapshotCmd("snap-300", 300L, 30L, "11000"));
        ctx.trigger.handle(warningCmd("w300", 300L, "11500"));
    }

    @Test
    void defaultsToTheLast28DaysEndingTodayInLima() {
        AnalyticsSummary s = handler.summarize(ORG, null, null, null);

        assertThat(s.zone()).isEqualTo("America/Lima");
        assertThat(s.to()).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(s.from()).isEqualTo(LocalDate.parse("2026-10-14"));
        assertThat(s.generatedAt()).isEqualTo(at(12, 0));
        assertThat(history.calls.get(0)).containsExactly(
                Instant.parse("2026-10-14T05:00:00Z"), Instant.parse("2026-11-11T05:00:00Z"));
    }

    @Test
    void fromAndToAreWholeDaysInTheRequestedZone() {
        handler.summarize(ORG, LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-08"), "UTC");

        assertThat(history.calls.get(0)).containsExactly(
                Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-09T00:00:00Z"));
    }

    @Test
    void onlyFromGoesUntilToday() {
        AnalyticsSummary s = handler.summarize(ORG, LocalDate.parse("2026-11-01"), null, " America/Lima ");

        assertThat(s.from()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(s.to()).isEqualTo(LocalDate.parse("2026-11-10"));
    }

    @Test
    void invalidZoneOrPeriodIs400() {
        assertThatThrownBy(() -> handler.summarize(ORG, null, null, "Mars/Olympus"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> handler.summarize(ORG, null, null, "+99:00"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> handler.summarize(ORG, LocalDate.parse("2026-11-10"),
                LocalDate.parse("2026-11-09"), null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> handler.summarize(ORG, LocalDate.parse("2025-11-09"),
                LocalDate.parse("2026-11-10"), null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> handler.summarize(null, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatCode(() -> handler.summarize(ORG, LocalDate.parse("2025-11-10"),
                LocalDate.parse("2026-11-10"), null)).doesNotThrowAnyException();
    }

    @Test
    void projectedCriticalWorkersAreTheOpenOnesMostUrgentFirst() {
        seedAssessments();

        List<AnalyticsSummary.ProjectedCritical> critical = handler.summarize(ORG, null, null, null)
                .projectedCriticalWorkers();

        assertThat(critical).extracting(AnalyticsSummary.ProjectedCritical::workerId).containsExactly(300L, 101L);
        assertThat(critical.get(0).criticality()).isEqualTo(CriticalityLevel.CRITICAL);
        assertThat(critical.get(0).recommendationType()).isEqualTo(RecommendationType.RESCHEDULE_TASK);
        assertThat(critical.get(1).recommendationType()).isEqualTo(RecommendationType.ROTATION);
        assertThat(critical.get(1).targetWorkerId()).isEqualTo(200L);
        assertThat(critical.get(1).estimatedExceedanceAt()).isEqualTo(at(14, 40));
    }

    @Test
    void projectedCriticalWorkersExcludeSessionsAlreadyClosed() {
        seedAssessments();
        history.closed(session(300L), 300L, 30L, "11900", at(11, 59));

        List<AnalyticsSummary.ProjectedCritical> critical = handler.summarize(ORG, null, null, null)
                .projectedCriticalWorkers();

        assertThat(critical).extracting(AnalyticsSummary.ProjectedCritical::workerId).containsExactly(101L);
        // la consulta de exclusión arranca en el inicio de la jornada más antigua (08:00)
        assertThat(history.calls.get(1)[0]).isEqualTo(at(8, 0));
    }

    @Test
    void recommendationsCountOnlyTheLatestAssessmentOfEachSession() {
        seedAssessments();
        ctx.trigger.handle(warningCmd("w101-bis", 101L, "7300")); // reevalúa S-101: la anterior queda SUPERSEDED

        AnalyticsSummary.Recommendations r = handler.summarize(ORG, null, null, null).recommendations();

        assertThat(r.rotation()).isEqualTo(1);
        assertThat(r.rescheduleTask()).isEqualTo(1);
        assertThat(r.estimatedBenefitIndex()).isPositive();
    }

    @Test
    void positionNamesComeFromTheCatalog() {
        positionNames = Map.of(10L, "Prensa");
        history.closed("S-a", 101L, 10L, "9000", at(10, 0));
        history.closed("S-b", 102L, 20L, "1000", at(10, 0));

        List<AnalyticsSummary.PositionStats> positions = handler.summarize(ORG, null, null, null).topPositions();

        assertThat(positions).extracting(AnalyticsSummary.PositionStats::positionName).containsExactly("Prensa", null);
    }
}
