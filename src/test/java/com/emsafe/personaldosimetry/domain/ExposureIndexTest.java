package com.emsafe.personaldosimetry.domain;

import com.emsafe.personaldosimetry.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class ExposureIndexTest {
    private static final Instant START = Instant.parse("2026-11-10T12:00:00Z");

    @ParameterizedTest
    @CsvSource({"0,1000000,0", "2.5,2000000,5", "0.000001,1,0.000000000001", "0.1,300000,0.03"})
    void integratesMagneticFieldWithoutIntermediateRounding(String field, long micros, String expected) {
        assertThat(ExposureIndex.contribution(new BigDecimal(field), START, START.plusNanos(micros * 1000)))
                .isEqualByComparingTo(expected);
    }

    @Test
    void rejectsNegativeField() {
        assertThatThrownBy(() -> ExposureIndex.contribution(new BigDecimal("-1"), START, START.plusSeconds(1)))
                .isInstanceOf(DosimetryRuleException.class);
    }

    @Test
    void rejectsIntervalsThatDatabaseWouldTruncate() {
        assertThatThrownBy(() -> ExposureIndex.contribution(BigDecimal.ONE, START, START.plusNanos(1)))
                .isInstanceOf(DosimetryRuleException.class);
    }

    @Test
    void rejectsZeroOrReversedIntervals() {
        assertThatThrownBy(() -> ExposureIndex.contribution(BigDecimal.ONE, START, START))
                .isInstanceOf(DosimetryRuleException.class);
        assertThatThrownBy(() -> ExposureIndex.contribution(BigDecimal.ONE, START, START.minusSeconds(1)))
                .isInstanceOf(DosimetryRuleException.class);
    }

    @Test
    void roundsOnlyTheDisplayedPercentage() {
        assertThat(ExposureIndex.percentage(new BigDecimal("0.800001"), BigDecimal.ONE))
                .isEqualByComparingTo("80.00");
        assertThat(ExposureIndex.percentage(BigDecimal.ONE, new BigDecimal("3")))
                .isEqualByComparingTo("33.33");
    }

    @Test
    void rejectsPrecisionThatWouldBeSilentlyRoundedOnPersistence() {
        assertThatThrownBy(() -> ExposureIndex.contribution(new BigDecimal("0.0000001"), START, START.plusSeconds(1)))
                .isInstanceOf(DosimetryRuleException.class);
    }

    @Test
    void hugeExponentCannotBypassPrecisionValidationThroughIntegerOverflow() {
        assertThatThrownBy(() -> ExposureIndex.decimal(new BigDecimal("1E+2147483647"), 12, 6, "field"))
                .isInstanceOf(DosimetryRuleException.class);
    }

    @Test
    void rejectsDatesOutsideMysqlRangeWithoutFailingDuringDateConversion() {
        assertThatThrownBy(() -> ExposureIndex.timestamp(Instant.MAX.truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
                .isInstanceOf(DosimetryRuleException.class);
    }
}
