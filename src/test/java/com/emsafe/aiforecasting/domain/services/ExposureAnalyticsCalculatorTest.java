package com.emsafe.aiforecasting.domain.services;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.ClosedExposureSession;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Escenario del diseño B2: 3 operarios × 4 jornadas en 2 semanas ISO (W46 y W47 de 2026), límite
 * 12000 uT·s, advertencia en 0.8 (9600) y "cerca del límite" en 0.90 (10800).
 */
class ExposureAnalyticsCalculatorTest {

    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final LocalDate FROM = LocalDate.parse("2026-11-09");
    private static final LocalDate TO = LocalDate.parse("2026-11-22");
    private static final Instant NOW = Instant.parse("2026-11-25T15:00:00Z");
    private static final BigDecimal LIMIT = new BigDecimal("12000");
    private static final BigDecimal WARNING = new BigDecimal("9600");

    private final ExposureAnalyticsCalculator calculator = new ExposureAnalyticsCalculator(new BigDecimal("0.90"));

    private int seq;

    /** Jornada que termina a las 16:00 de Lima (21:00 UTC) del día indicado. */
    private ClosedExposureSession session(long worker, long position, String finalIndex, String day) {
        BigDecimal value = new BigDecimal(finalIndex);
        Instant end = Instant.parse(day + "T21:00:00Z");
        return new ClosedExposureSession("S-" + (++seq), worker, position, value, LIMIT,
                value.compareTo(WARNING) > 0, value.compareTo(LIMIT) > 0, end.minusSeconds(8 * 3600), end, true);
    }

    private List<ClosedExposureSession> designScenario() {
        return List.of(
                session(101, 10, "11400", "2026-11-10"), session(101, 10, "10900", "2026-11-11"),
                session(200, 20, "2900", "2026-11-10"), session(200, 20, "3100", "2026-11-11"),
                session(300, 30, "10200", "2026-11-10"), session(300, 30, "11100", "2026-11-11"),
                session(101, 10, "12300", "2026-11-17"), session(101, 10, "9800", "2026-11-18"),
                session(200, 20, "2700", "2026-11-17"), session(200, 20, "3300", "2026-11-18"),
                session(300, 30, "9100", "2026-11-17"), session(300, 30, "10800", "2026-11-18"));
    }

    private AnalyticsSummary summarize(List<ClosedExposureSession> sessions, Map<Long, String> names) {
        return calculator.summarize(8L, FROM, TO, LIMA, NOW, sessions, List.of(), List.of(), names);
    }

    @Test
    void nearLimitRatioMustBeInZeroOne() {
        assertThatThrownBy(() -> new ExposureAnalyticsCalculator(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExposureAnalyticsCalculator(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExposureAnalyticsCalculator(new BigDecimal("1.01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ExposureAnalyticsCalculator(BigDecimal.ONE)).doesNotThrowAnyException();
    }

    @Test
    void totalsOfTheDesignScenario() {
        AnalyticsSummary.Totals t = summarize(designScenario(), Map.of()).totals();

        assertThat(t.closedSessions()).isEqualTo(12);
        assertThat(t.workers()).isEqualTo(3);
        assertThat(t.sessionsAboveWarning()).isEqualTo(7);
        assertThat(t.sessionsNearLimit()).isEqualTo(5);
        assertThat(t.sessionsNearLimitPct()).isEqualByComparingTo("41.67");
        assertThat(t.sessionsExceeded()).isEqualTo(1);
        assertThat(t.sessionsExceededPct()).isEqualByComparingTo("8.33");
        assertThat(t.incompleteCoverageSessions()).isZero();
        assertThat(t.nearLimitRatio()).isEqualByComparingTo("0.90");
    }

    @Test
    void perWorkerIsSortedByAverageDescending() {
        List<AnalyticsSummary.WorkerStats> workers = summarize(designScenario(), Map.of()).perWorker();

        assertThat(workers).extracting(AnalyticsSummary.WorkerStats::workerId).containsExactly(101L, 300L, 200L);
        AnalyticsSummary.WorkerStats w101 = workers.get(0);
        assertThat(w101.sessions()).isEqualTo(4);
        assertThat(w101.avgFinalIndex()).isEqualByComparingTo("11100");
        assertThat(w101.maxFinalIndex()).isEqualByComparingTo("12300");
        assertThat(w101.maxRatio()).isEqualByComparingTo("1.025");
        assertThat(w101.sessionsAboveWarning()).isEqualTo(4);
        assertThat(w101.sessionsNearLimit()).isEqualTo(3);
        assertThat(w101.sessionsExceeded()).isEqualTo(1);
        assertThat(workers.get(1).sessionsAboveWarning()).isEqualTo(3);
        assertThat(workers.get(2).avgFinalIndex()).isEqualByComparingTo("3000");
    }

    @Test
    void weeklyTrendByIsoWeek() {
        List<AnalyticsSummary.WeekStats> weeks = summarize(designScenario(), Map.of()).weeklyTrend();

        assertThat(weeks).extracting(AnalyticsSummary.WeekStats::isoWeek).containsExactly("2026-W46", "2026-W47");
        assertThat(weeks.get(0).weekStart()).isEqualTo(LocalDate.parse("2026-11-09"));
        assertThat(weeks.get(0).sessions()).isEqualTo(6);
        assertThat(weeks.get(0).avgFinalIndex()).isEqualByComparingTo("8266.666667");
        assertThat(weeks.get(0).maxFinalIndex()).isEqualByComparingTo("11400");
        assertThat(weeks.get(0).sessionsNearLimit()).isEqualTo(3);
        assertThat(weeks.get(0).sessionsExceeded()).isZero();
        assertThat(weeks.get(1).avgFinalIndex()).isEqualByComparingTo("8000");
        assertThat(weeks.get(1).maxFinalIndex()).isEqualByComparingTo("12300");
        assertThat(weeks.get(1).sessionsNearLimit()).isEqualTo(2);
        assertThat(weeks.get(1).sessionsExceeded()).isEqualTo(1);
    }

    @Test
    void topPositionsCarryTheKnownNamesAndNullOtherwise() {
        List<AnalyticsSummary.PositionStats> positions =
                summarize(designScenario(), Map.of(10L, "Prensa")).topPositions();

        assertThat(positions).extracting(AnalyticsSummary.PositionStats::positionId).containsExactly(10L, 30L, 20L);
        assertThat(positions.get(0).positionName()).isEqualTo("Prensa");
        assertThat(positions.get(1).positionName()).isNull();
        assertThat(positions.get(0).avgFinalIndex()).isEqualByComparingTo("11100");
        assertThat(positions.get(0).maxFinalIndex()).isEqualByComparingTo("12300");
    }

    @Test
    void topPositionsAreCappedAtTen() {
        List<ClosedExposureSession> sessions = new ArrayList<>();
        for (long p = 1; p <= 12; p++) {
            sessions.add(session(100 + p, p, String.valueOf(p * 100), "2026-11-10"));
        }

        List<AnalyticsSummary.PositionStats> positions = summarize(sessions, Map.of()).topPositions();

        assertThat(positions).hasSize(ExposureAnalyticsCalculator.TOP_POSITIONS);
        assertThat(positions.get(0).positionId()).isEqualTo(12L);
    }

    @Test
    void workersAboveWarningByCountThenLatest() {
        List<AnalyticsSummary.WarningWorker> warned = summarize(designScenario(), Map.of()).workersAboveWarning();

        assertThat(warned).extracting(AnalyticsSummary.WarningWorker::workerId).containsExactly(101L, 300L);
        assertThat(warned.get(0).sessionsAboveWarning()).isEqualTo(4);
        assertThat(warned.get(0).lastAt()).isEqualTo(Instant.parse("2026-11-18T21:00:00Z"));
        assertThat(warned.get(1).sessionsAboveWarning()).isEqualTo(3);
    }

    @Test
    void exactlyTheNearLimitRatioCountsAndJustBelowDoesNot() {
        ClosedExposureSession exact = session(1, 1, "10800", "2026-11-10");
        ClosedExposureSession below = session(2, 1, "10799.999999", "2026-11-10");

        assertThat(calculator.nearLimit(exact)).isTrue();
        assertThat(calculator.nearLimit(below)).isFalse();
    }

    @Test
    void withoutSessionsPercentagesAreNullAndEveryWeekIsListed() {
        AnalyticsSummary s = summarize(List.of(), Map.of());

        assertThat(s.totals().closedSessions()).isZero();
        assertThat(s.totals().sessionsNearLimitPct()).isNull();
        assertThat(s.totals().sessionsExceededPct()).isNull();
        assertThat(s.perWorker()).isEmpty();
        assertThat(s.topPositions()).isEmpty();
        assertThat(s.workersAboveWarning()).isEmpty();
        assertThat(s.weeklyTrend()).hasSize(2).allSatisfy(w -> {
            assertThat(w.sessions()).isZero();
            assertThat(w.avgFinalIndex()).isNull();
            assertThat(w.maxFinalIndex()).isNull();
        });
        assertThat(s.recommendations().rotation()).isZero();
        assertThat(s.recommendations().estimatedBenefitIndex()).isEqualByComparingTo("0");
    }

    @Test
    void theWeekDependsOnTheRequestedZone() {
        // Lunes 16 a las 03:00 UTC = domingo 15 a las 22:00 en Lima.
        Instant end = Instant.parse("2026-11-16T03:00:00Z");
        ClosedExposureSession late = new ClosedExposureSession("S-late", 1L, 1L, new BigDecimal("100"), LIMIT,
                false, false, end.minusSeconds(3600), end, true);

        List<AnalyticsSummary.WeekStats> lima = calculator.summarize(8L, FROM, TO, LIMA, NOW, List.of(late),
                List.of(), List.of(), Map.of()).weeklyTrend();
        List<AnalyticsSummary.WeekStats> utc = calculator.summarize(8L, FROM, TO, ZoneOffset.UTC, NOW, List.of(late),
                List.of(), List.of(), Map.of()).weeklyTrend();

        assertThat(lima.get(0).sessions()).isEqualTo(1);
        assertThat(lima.get(1).sessions()).isZero();
        assertThat(utc.get(0).sessions()).isZero();
        assertThat(utc.get(1).sessions()).isEqualTo(1);
    }

    @Test
    void isoWeeksAcrossTheYearBoundary() {
        assertThat(ExposureAnalyticsCalculator.isoWeek(LocalDate.parse("2026-12-28"))).isEqualTo("2026-W53");
        assertThat(ExposureAnalyticsCalculator.isoWeek(LocalDate.parse("2027-01-01"))).isEqualTo("2026-W53");
        assertThat(ExposureAnalyticsCalculator.isoWeek(LocalDate.parse("2027-01-04"))).isEqualTo("2027-W01");
        assertThat(ExposureAnalyticsCalculator.weekStart(LocalDate.parse("2027-01-01")))
                .isEqualTo(LocalDate.parse("2026-12-28"));
    }
}
