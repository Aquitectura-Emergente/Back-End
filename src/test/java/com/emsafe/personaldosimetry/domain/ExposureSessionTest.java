package com.emsafe.personaldosimetry.domain;

import com.emsafe.personaldosimetry.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class ExposureSessionTest {
    private static final Instant START = Instant.parse("2026-11-10T12:00:00Z");

    private ExposureSession open() {
        var limit = new OccupationalLimit(1L, 4L, 1, new BigDecimal("100"), new BigDecimal("0.8"),
                START, "DEMO", 1L, START);
        ReflectionTestUtils.setField(limit, "id", 10L);
        return ExposureSession.open(1L, 2L, 3L, limit, START);
    }

    @Test
    void comparesUnroundedAccumulatedValueAtBothBoundaries() {
        var s = open();
        assertThat(s.apply(new BigDecimal("80"), 1_000_000, START.plusSeconds(1)).warning()).isFalse();
        assertThat(s.apply(new BigDecimal("0.000001"), 1_000_000, START.plusSeconds(2)).warning()).isTrue();
        assertThat(s.percentage()).isEqualByComparingTo("80.00");
        assertThat(s.apply(new BigDecimal("19.999999"), 1_000_000, START.plusSeconds(3)).exceeded()).isFalse();
        assertThat(s.apply(new BigDecimal("0.000001"), 1_000_000, START.plusSeconds(4)).exceeded()).isTrue();
        var repeated = s.apply(BigDecimal.ONE, 1_000_000, START.plusSeconds(5));
        assertThat(repeated.warning()).isFalse();
        assertThat(repeated.exceeded()).isFalse();
    }

    @Test
    void closeReleasesAssignmentButAllowsPendingCaptures() {
        var s = open();
        s.requestClose(START.plusSeconds(60), START.plusSeconds(60));
        assertThat(s.getState()).isEqualTo(ExposureSession.State.CLOSING);
        assertThat(s.getActiveWorkerId()).isNull();
        assertThat(s.getActiveDeviceId()).isNull();
        s.apply(BigDecimal.ONE, 1_000_000, START.plusSeconds(10));
        assertThat(s.getAccumulatedIndex()).isEqualByComparingTo("1");
    }

    @Test
    void reconciliationRequiresDeliveryConfirmationOrDeadline() {
        var s = open();
        s.requestClose(START.plusSeconds(60), START.plusSeconds(60));
        assertThatThrownBy(() -> s.finalizeSession(START.plusSeconds(61), false))
                .isInstanceOf(DosimetryRuleException.class);
        s.finalizeSession(START.plusSeconds(61), true);
        assertThat(s.getState()).isEqualTo(ExposureSession.State.CLOSED);
        assertThat(s.coverageComplete()).isFalse();
    }

    @Test
    void deadlineCanFinalizeIncompleteCoverage() {
        var s = open();
        s.requestClose(START.plusSeconds(60), START.plusSeconds(60));
        s.finalizeSession(START.plusSeconds(1860), false);
        assertThat(s.coverage(START.plusSeconds(3000))).isEqualByComparingTo("0");
        assertThat(s.coverageComplete()).isFalse();
    }

    @Test
    void closedSessionRejectsChanges() {
        var s = open();
        s.apply(BigDecimal.ONE, 1_000_000, START.plusSeconds(1));
        s.requestClose(START.plusSeconds(1), START.plusSeconds(1));
        s.finalizeSession(START.plusSeconds(1), true);
        assertThat(s.coverageComplete()).isTrue();
        assertThatThrownBy(() -> s.apply(BigDecimal.TEN, 1_000_000, START.plusSeconds(1)))
                .isInstanceOf(DosimetryRuleException.class);
        assertThat(s.getAccumulatedIndex()).isEqualByComparingTo("1");
    }

    @Test
    void rejectsCapturesOutsideTheAssignmentAndInTheFuture() {
        var s = open();
        assertThatThrownBy(() -> s.validateCapture(START.minusSeconds(1), START, START.plusSeconds(10)))
                .isInstanceOf(DosimetryRuleException.class);
        assertThatThrownBy(() -> s.validateCapture(START, START.plusSeconds(11), START.plusSeconds(10)))
                .isInstanceOf(DosimetryRuleException.class);
    }

    @Test
    void cannotCloseBeforeAnAcceptedCapture() {
        var s = open();
        s.apply(BigDecimal.ONE, 10_000_000, START.plusSeconds(10));
        assertThatThrownBy(() -> s.requestClose(START.plusSeconds(9), START.plusSeconds(20)))
                .isInstanceOf(DosimetryRuleException.class);
    }

    @Test
    void roundedCoverageDoesNotHideMissingMicroseconds() {
        var s = open();
        s.apply(BigDecimal.ONE, 999_999, START.plusNanos(999_999_000));
        s.requestClose(START.plusSeconds(1), START.plusSeconds(1));
        s.finalizeSession(START.plusSeconds(1), true);
        assertThat(s.coverage(START.plusSeconds(1))).isEqualByComparingTo("100.00");
        assertThat(s.coverageComplete()).isFalse();
    }
}
