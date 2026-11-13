package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.ExposureMethod;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import org.junit.jupiter.api.Test;

import static com.emsafe.aiforecasting.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class WorkerExposureSnapshotTest {

    private final WorkerExposureSnapshot current = snapshot(101L, 10L, "3600", "12000", at(8, 0), at(12, 0));

    @Test
    void ownershipMismatchIsAConflict() {
        assertThatCode(() -> current.ensureBelongsTo(ORG, 101L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> current.ensureBelongsTo(ORG, 102L))
                .isInstanceOfSatisfying(ForecastingRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ForecastingRuleException.Kind.CONFLICT));
        assertThatThrownBy(() -> current.ensureBelongsTo(2L, 101L)).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void mergeKeepsZonePositionAndShiftEndWhenIncomingOmitsThem() {
        WorkerExposureSnapshot incoming = new WorkerExposureSnapshot(ORG, 101L, "S-101", null, null,
                dec("4000"), dec("12000"), at(8, 0), null, at(12, 30), at(12, 30));

        WorkerExposureSnapshot merged = current.merge(incoming);

        assertThat(merged.accumulatedIndex()).isEqualByComparingTo("4000");
        assertThat(merged.capturedAt()).isEqualTo(at(12, 30));
        assertThat(merged.zoneId()).isEqualTo(ZONE);
        assertThat(merged.positionId()).isEqualTo(10L);
        assertThat(merged.plannedShiftEnd()).isEqualTo(at(16, 0));
    }

    @Test
    void mergeIgnoresOlderCapture() {
        WorkerExposureSnapshot older = snapshot(101L, 99L, "1000", "12000", at(8, 0), at(11, 0));

        assertThat(current.merge(older)).isSameAs(current);
    }

    @Test
    void mergeRejectsAnotherOwner() {
        WorkerExposureSnapshot other = snapshot(555L, 10L, "1", "12000", at(8, 0), at(12, 30));
        WorkerExposureSnapshot sameSessionOtherWorker = new WorkerExposureSnapshot(ORG, 555L, "S-101", ZONE, 10L,
                dec("1"), dec("12000"), at(8, 0), null, at(12, 30), at(12, 30));

        assertThatThrownBy(() -> current.merge(other)).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> current.merge(sameSessionOtherWorker)).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void captureUpdatesIndexOnlyWhenNotOlder() {
        WorkerExposureSnapshot newer = current.withCapture(dec("7200"), dec("11000"), at(12, 0), at(12, 5));
        WorkerExposureSnapshot stale = current.withCapture(dec("100"), dec("11000"), at(11, 0), at(12, 5));

        assertThat(newer.accumulatedIndex()).isEqualByComparingTo("7200");
        assertThat(newer.limitIndex()).isEqualByComparingTo("11000");
        assertThat(newer.updatedAt()).isEqualTo(at(12, 5));
        assertThat(newer.positionId()).isEqualTo(10L);
        assertThat(stale).isSameAs(current);
    }

    @Test
    void relocationKeepsValuesThatAreNotSent() {
        WorkerExposureSnapshot moved = current.withShift(null, 20L, null, at(12, 5));

        assertThat(moved.zoneId()).isEqualTo(ZONE);
        assertThat(moved.positionId()).isEqualTo(20L);
        assertThat(moved.plannedShiftEnd()).isEqualTo(at(16, 0));
    }

    @Test
    void onlyMagneticTimeIndexInMicroteslaSecondsIsSupported() {
        assertThatCode(() -> ExposureMethod.ensureSupported("MAGNETIC_TIME_INDEX_V1", "uT*s"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ExposureMethod.ensureSupported("OTHER", "uT*s"))
                .isInstanceOfSatisfying(ForecastingRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ForecastingRuleException.Kind.INVALID_RULE));
        assertThatThrownBy(() -> ExposureMethod.ensureSupported("MAGNETIC_TIME_INDEX_V1", "mSv"))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> ExposureMethod.ensureSupported(null, null))
                .isInstanceOf(ForecastingRuleException.class);
    }
}
