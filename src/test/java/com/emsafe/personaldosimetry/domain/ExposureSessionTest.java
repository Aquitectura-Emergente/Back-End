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

    @Test
    void changePositionPreservesAccumulatedExposureAndReevaluatesHigherLimit() {
        var s = open(); // position 4L, limit 100, warningRatio 0.8 (80)
        assertThat(s.apply(new BigDecimal("85"), 1_000_000, START.plusSeconds(1)).warning()).isTrue();
        assertThat(s.isWarningRaised()).isTrue();

        var saferLimit = new OccupationalLimit(1L, 5L, 1, new BigDecimal("250"), new BigDecimal("0.8"),
                START, "SAFE-POS", 1L, START);
        ReflectionTestUtils.setField(saferLimit, "id", 11L);

        var crossings = s.changePosition(saferLimit, START.plusSeconds(2));
        assertThat(crossings.warning()).isFalse();
        assertThat(crossings.exceeded()).isFalse();
        assertThat(s.getAccumulatedIndex()).isEqualByComparingTo("85");
        assertThat(s.getJobPositionId()).isEqualTo(5L);
        assertThat(s.getLimitVersionId()).isEqualTo(11L);
        assertThat(s.getLimitIndex()).isEqualByComparingTo("250");
        assertThat(s.isWarningRaised()).isFalse();

        // Si en el puesto nuevo vuelve a superar el umbral (200), vuelve a avisar.
        assertThat(s.apply(new BigDecimal("116"), 1_000_000, START.plusSeconds(3)).warning()).isTrue();
    }

    @Test
    void changePositionToStricterLimitReportsImmediateCrossingsOnce() {
        var s = open(); // limit 100
        s.apply(new BigDecimal("60"), 1_000_000, START.plusSeconds(1));

        var stricterLimit = new OccupationalLimit(1L, 6L, 1, new BigDecimal("50"), new BigDecimal("0.8"),
                START, "STRICT-POS", 1L, START);
        ReflectionTestUtils.setField(stricterLimit, "id", 12L);

        var first = s.changePosition(stricterLimit, START.plusSeconds(2));
        assertThat(first.warning()).isTrue();
        assertThat(first.exceeded()).isTrue();
        assertThat(s.getAccumulatedIndex()).isEqualByComparingTo("60");

        var duplicate = s.changePosition(stricterLimit, START.plusSeconds(2));
        assertThat(duplicate.warning()).isFalse();
        assertThat(duplicate.exceeded()).isFalse();
    }

    @Test
    void changePositionRejectsNonOpenSessionsOrInvalidLimits() {
        var s = open();
        var otherOrgLimit = new OccupationalLimit(99L, 5L, 1, new BigDecimal("200"), new BigDecimal("0.8"),
                START, "OTHER", 1L, START);
        ReflectionTestUtils.setField(otherOrgLimit, "id", 13L);
        assertThatThrownBy(() -> s.changePosition(otherOrgLimit, START.plusSeconds(1)))
                .isInstanceOf(DosimetryRuleException.class);

        var futureLimit = new OccupationalLimit(1L, 5L, 1, new BigDecimal("200"), new BigDecimal("0.8"),
                START.plusSeconds(60), "FUTURE", 1L, START);
        ReflectionTestUtils.setField(futureLimit, "id", 14L);
        assertThatThrownBy(() -> s.changePosition(futureLimit, START.plusSeconds(10)))
                .isInstanceOf(DosimetryRuleException.class);

        s.requestClose(START.plusSeconds(10), START.plusSeconds(10));
        var validLimit = new OccupationalLimit(1L, 5L, 1, new BigDecimal("200"), new BigDecimal("0.8"),
                START, "VALID", 1L, START);
        ReflectionTestUtils.setField(validLimit, "id", 15L);
        assertThatThrownBy(() -> s.changePosition(validLimit, START.plusSeconds(10)))
                .isInstanceOf(DosimetryRuleException.class);
    }
}
