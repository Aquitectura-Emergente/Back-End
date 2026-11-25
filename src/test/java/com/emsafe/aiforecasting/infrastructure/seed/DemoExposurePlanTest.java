package com.emsafe.aiforecasting.infrastructure.seed;

import com.emsafe.aiforecasting.infrastructure.seed.DemoExposurePlan.PlannedSession;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El guion de la demo: lo que debería ver la analítica después de sembrar. */
class DemoExposurePlanTest {

    /** Miércoles 2026-11-25 a las 10:00 de Lima. */
    private static final Instant NOW = Instant.parse("2026-11-25T15:00:00Z");
    private static final BigDecimal WARNING = DemoExposurePlan.LIMIT_INDEX.multiply(DemoExposurePlan.WARNING_RATIO);
    private static final BigDecimal NEAR = DemoExposurePlan.LIMIT_INDEX.multiply(new BigDecimal("0.90"));

    private final List<PlannedSession> history = DemoExposurePlan.history(NOW);

    @Test
    void sixtyWeekdaySessionsOfEightHoursInLimaBeforeToday() {
        assertThat(history).hasSize(60);
        assertThat(history).allSatisfy(s -> {
            assertThat(s.day().getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
            assertThat(s.day()).isBefore(DemoExposurePlan.today(NOW))
                    .isAfterOrEqualTo(DemoExposurePlan.today(NOW).minusDays(DemoExposurePlan.HISTORY_DAYS));
            assertThat(s.start().atZone(DemoExposurePlan.ZONE).toLocalTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(Duration.between(s.start(), s.end())).isEqualTo(Duration.ofHours(8));
            assertThat(s.readingTo()).isAfter(s.start()).isBeforeOrEqualTo(s.end());
            assertThat(s.fieldMicrotesla().scale()).isLessThanOrEqualTo(6);
        });
        assertThat(history).extracting(PlannedSession::worker).containsOnly(0, 1, 2, 3);
        assertThat(DemoExposurePlan.historyStart(NOW)).isEqualTo(Instant.parse("2026-11-04T05:00:00Z"));
    }

    @Test
    void exactlyOneSessionExceedsTheLimit() {
        List<PlannedSession> exceeded = history.stream()
                .filter(s -> s.expectedIndex().compareTo(DemoExposurePlan.LIMIT_INDEX) > 0).toList();

        assertThat(exceeded).hasSize(1);
        assertThat(exceeded.get(0).worker()).isZero();
    }

    @Test
    void nineSessionsAboveTheWarningTwoNearTheLimitAndOneIncomplete() {
        assertThat(history.stream().filter(s -> s.expectedIndex().compareTo(WARNING) > 0)).hasSize(9);
        assertThat(history.stream().filter(s -> s.expectedIndex().compareTo(NEAR) >= 0)).hasSize(2);
        assertThat(history.stream().filter(s -> !s.readingTo().equals(s.end()))).singleElement()
                .satisfies(s -> assertThat(s.worker()).isEqualTo(DemoExposurePlan.INCOMPLETE_WORKER));
    }

    @Test
    void captureIdentitiesAreUnique() {
        assertThat(history.stream().map(s -> s.worker() + "/" + s.bootId())).doesNotHaveDuplicates();
    }

    @Test
    void todaysOpenSessionsPutTheFirstTwoWorkersAboveTheWarning() {
        for (int w = 0; w < DemoExposurePlan.WORKERS.size(); w++) {
            PlannedSession open = DemoExposurePlan.open(w, NOW);
            assertThat(open.end()).isNull();
            assertThat(open.readingTo()).isEqualTo(NOW.minusSeconds(60));
            assertThat(Duration.between(open.start(), NOW)).isEqualTo(Duration.ofHours(4));
            assertThat(open.expectedIndex().compareTo(WARNING) > 0).isEqualTo(w < 2);
            assertThat(open.expectedIndex()).isLessThan(DemoExposurePlan.LIMIT_INDEX);
        }
        // abiertas hoy después de cerrar el último día hábil del historial
        assertThat(DemoExposurePlan.open(0, NOW).start()).isAfter(history.get(history.size() - 1).end());
    }

    @Test
    void theRealWorkerOfDataInitializerComesFirstAndPositionsAreKnown() {
        assertThat(DemoExposurePlan.WORKERS.get(0).email()).isEqualTo("operario1@quantumdyn.com");
        assertThat(DemoExposurePlan.WORKERS).extracting(DemoExposurePlan.Worker::position)
                .allSatisfy(p -> assertThat(p).isBetween(0, DemoExposurePlan.POSITIONS.size() - 1));
        assertThat(DemoExposurePlan.WORKERS).extracting(DemoExposurePlan.Worker::dosimeterSerial)
                .doesNotHaveDuplicates();
    }
}
