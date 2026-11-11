package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.ForecastingThresholds;
import com.emsafe.aiforecasting.domain.model.ModelVersion;
import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.emsafe.aiforecasting.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class ValueObjectsTest {

    @Test
    void modelVersionHasCode() {
        assertThat(MODEL.code()).isEqualTo("LINEAR_RATE_V1");
        assertThatThrownBy(() -> new ModelVersion(" ", "1")).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new ModelVersion("LINEAR_RATE", null)).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void redactionRequiresText() {
        assertThatThrownBy(() -> new NaturalLanguageRedaction(" ", RedactionSource.LLM, at(10, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new NaturalLanguageRedaction("ok", null, at(10, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new NaturalLanguageRedaction("ok", RedactionSource.LLM, null))
                .isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void rotationRequiresTargetsAndRescheduleForbidsThem() {
        assertThatThrownBy(() -> new RotationRecommendation(RecommendationType.ROTATION,
                101L, null, 10L, 20L, dec("1"), "r")).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new RotationRecommendation(RecommendationType.ROTATION,
                101L, 200L, 10L, null, dec("1"), "r")).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new RotationRecommendation(RecommendationType.RESCHEDULE_TASK,
                101L, 200L, 10L, null, dec("1"), "r")).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new RotationRecommendation(RecommendationType.ROTATION,
                101L, 101L, 10L, 20L, dec("1"), "r")).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void recommendationRejectsNegativeBenefitAndBlankReason() {
        assertThatThrownBy(() -> new RotationRecommendation(RecommendationType.RESCHEDULE_TASK,
                101L, null, 10L, null, dec("-1"), "r")).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new RotationRecommendation(RecommendationType.RESCHEDULE_TASK,
                101L, null, 10L, null, dec("1"), "")).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void thresholdsMustBeRatiosInZeroOne() {
        assertThatThrownBy(() -> new ForecastingThresholds(dec("0"), dec("0.9"), dec("0.9")))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new ForecastingThresholds(dec("0.1"), dec("1.5"), dec("0.9")))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new ForecastingThresholds(dec("0.1"), dec("0.9"), null))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatCode(() -> new ForecastingThresholds(dec("0.1"), dec("1"), dec("1"))).doesNotThrowAnyException();
    }

    @Test
    void projectionRejectsMissingValuesAndNonPositiveLimit() {
        assertThatThrownBy(() -> new ExposureProjection(dec("1"), dec("0"), dec("0"), dec("1"), dec("-1"),
                dec("0"), dec("0"), null, at(16, 0))).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new ExposureProjection(dec("1"), dec("10"), dec("0"), dec("1"), dec("9"),
                dec("0.1"), dec("0.1"), null, null)).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void snapshotUsesPlannedEndOrDefaultShift() {
        WorkerExposureSnapshot planned = snapshot(101L, 10L, "0", "100", at(8, 0), at(9, 0));
        WorkerExposureSnapshot unplanned = new WorkerExposureSnapshot(ORG, 101L, "S-101", ZONE, 10L,
                dec("0"), dec("100"), at(8, 0), null, at(9, 0), at(9, 0));

        assertThat(planned.shiftEnd(Duration.ofHours(4))).isEqualTo(at(16, 0));
        assertThat(unplanned.shiftEnd(Duration.ofHours(4))).isEqualTo(at(12, 0));
    }

    @Test
    void snapshotRejectsMissingIdentifiersAndNegativeIndex() {
        assertThatThrownBy(() -> new WorkerExposureSnapshot(null, 101L, "S", ZONE, 10L,
                dec("0"), dec("100"), at(8, 0), null, at(9, 0), at(9, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new WorkerExposureSnapshot(ORG, 101L, "", ZONE, 10L,
                dec("0"), dec("100"), at(8, 0), null, at(9, 0), at(9, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> new WorkerExposureSnapshot(ORG, 101L, "S", ZONE, 10L,
                dec("-1"), dec("100"), at(8, 0), null, at(9, 0), at(9, 0)))
                .isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void snapshotCanBeRelocated() {
        WorkerExposureSnapshot s = snapshot(101L, 10L, "0", "100", at(8, 0), at(9, 0));

        WorkerExposureSnapshot moved = s.withShift("Z-9", 99L, at(18, 0), at(9, 30));

        assertThat(moved.zoneId()).isEqualTo("Z-9");
        assertThat(moved.positionId()).isEqualTo(99L);
        assertThat(moved.plannedShiftEnd()).isEqualTo(at(18, 0));
        assertThat(moved.updatedAt()).isEqualTo(at(9, 30));
        assertThat(moved.accumulatedIndex()).isEqualTo(s.accumulatedIndex());
    }
}
