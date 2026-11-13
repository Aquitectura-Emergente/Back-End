package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.services.ExposureProjectionService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.emsafe.aiforecasting.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class ExposureProjectionServiceTest {

    private final ExposureProjectionService service = new ExposureProjectionService();

    @Test
    void projectsLinearlyWithHandComputedValues() {
        // 3600 uT·s en 2 h (7200 s) → tasa 0.5; quedan 6 h (21600 s) → +10800.
        ExposureProjection p = service.project(dec("3600"), dec("12000"), at(8, 0), at(10, 0), at(16, 0));

        assertThat(p.currentIndex()).isEqualByComparingTo("3600");
        assertThat(p.limitIndex()).isEqualByComparingTo("12000");
        assertThat(p.accumulationRate()).isEqualByComparingTo("0.5");
        assertThat(p.projectedIndexAtShiftEnd()).isEqualByComparingTo("14400");
        assertThat(p.remainingMargin()).isEqualByComparingTo("8400");
        assertThat(p.consumedRatio()).isEqualByComparingTo("0.3");
        assertThat(p.projectedRatio()).isEqualByComparingTo("1.2");
        // (12000 − 3600) / 0.5 = 16800 s = 4 h 40 min después de las 10:00.
        assertThat(p.estimatedExceedanceAt()).isEqualTo(at(14, 40));
        assertThat(p.shiftEnd()).isEqualTo(at(16, 0));
    }

    @Test
    void allValuesUseScaleSixHalfUp() {
        // 1000 / 3 s = 333.333333…; quedan 3 s → 999.999999.
        ExposureProjection p = service.project(dec("1000"), dec("5000"),
                at(8, 0), at(8, 0).plusSeconds(3), at(8, 0).plusSeconds(6));

        assertThat(p.accumulationRate()).isEqualTo(dec("333.333333"));
        assertThat(p.projectedIndexAtShiftEnd()).isEqualTo(dec("1999.999999"));
        assertThat(p.consumedRatio()).isEqualTo(dec("0.200000"));
        assertThat(p.projectedRatio()).isEqualTo(dec("0.400000"));
        assertThat(p.remainingMargin().scale()).isEqualTo(6);
    }

    @Test
    void belowLimitHasNoEstimatedExceedance() {
        ExposureProjection p = service.project(dec("3600"), dec("20000"), at(8, 0), at(10, 0), at(16, 0));

        assertThat(p.projectedRatio()).isEqualByComparingTo("0.72");
        assertThat(p.estimatedExceedanceAt()).isNull();
    }

    @Test
    void projectionExactlyAtLimitIsNotAnExceedance() {
        ExposureProjection p = service.project(dec("3600"), dec("14400"), at(8, 0), at(10, 0), at(16, 0));

        assertThat(p.projectedRatio()).isEqualByComparingTo("1");
        assertThat(p.estimatedExceedanceAt()).isNull();
    }

    @Test
    void zeroRateKeepsTheCurrentIndex() {
        ExposureProjection p = service.project(dec("0"), dec("12000"), at(8, 0), at(10, 0), at(16, 0));

        assertThat(p.accumulationRate()).isEqualByComparingTo("0");
        assertThat(p.projectedIndexAtShiftEnd()).isEqualByComparingTo("0");
        assertThat(p.projectedRatio()).isEqualByComparingTo("0");
        assertThat(p.estimatedExceedanceAt()).isNull();
    }

    @Test
    void alreadyExceededReportsCaptureTimeAndNegativeMargin() {
        ExposureProjection p = service.project(dec("13000"), dec("12000"), at(8, 0), at(10, 0), at(16, 0));

        assertThat(p.remainingMargin()).isEqualByComparingTo("-1000");
        assertThat(p.estimatedExceedanceAt()).isEqualTo(at(10, 0));
    }

    @Test
    void currentExactlyAtLimitReportsCaptureTime() {
        ExposureProjection p = service.project(dec("12000"), dec("12000"), at(8, 0), at(10, 0), at(16, 0));

        assertThat(p.remainingMargin()).isEqualByComparingTo("0");
        assertThat(p.estimatedExceedanceAt()).isEqualTo(at(10, 0));
    }

    @Test
    void shiftAlreadyOverMeansNoRemainingAccumulation() {
        ExposureProjection p = service.project(dec("3600"), dec("12000"), at(8, 0), at(17, 0), at(16, 0));

        assertThat(p.projectedIndexAtShiftEnd()).isEqualByComparingTo("3600");
        assertThat(p.estimatedExceedanceAt()).isNull();
    }

    @Test
    void rejectsZeroOrNegativeElapsedTime() {
        assertThatThrownBy(() -> service.project(dec("10"), dec("100"), at(10, 0), at(10, 0), at(16, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> service.project(dec("10"), dec("100"), at(10, 0), at(9, 0), at(16, 0)))
                .isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void rejectsZeroOrNegativeLimit() {
        assertThatThrownBy(() -> service.project(dec("10"), dec("0"), at(8, 0), at(10, 0), at(16, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> service.project(dec("10"), dec("-1"), at(8, 0), at(10, 0), at(16, 0)))
                .isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void rejectsNegativeIndexAndMissingValues() {
        assertThatThrownBy(() -> service.project(dec("-1"), dec("100"), at(8, 0), at(10, 0), at(16, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> service.project(null, dec("100"), at(8, 0), at(10, 0), at(16, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> service.project(dec("1"), dec("100"), null, at(10, 0), at(16, 0)))
                .isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void projectsFromSnapshotUsingDefaultShiftWhenNoPlannedEnd() {
        WorkerExposureSnapshot s = new WorkerExposureSnapshot(ORG, 101L, "S-101", ZONE, 10L,
                dec("3600"), dec("12000"), at(8, 0), null, at(10, 0), at(10, 0));

        ExposureProjection p = service.project(s, Duration.ofHours(8));

        assertThat(p.shiftEnd()).isEqualTo(at(16, 0));
        assertThat(p.projectedIndexAtShiftEnd()).isEqualByComparingTo("14400");
    }

    @Test
    void computesRemainingSecondsNeverNegative() {
        Instant captured = at(10, 0);
        assertThat(ExposureProjectionService.remainingSeconds(captured, at(16, 0))).isEqualByComparingTo("21600");
        assertThat(ExposureProjectionService.remainingSeconds(captured, at(9, 0))).isEqualByComparingTo("0");
    }
}
