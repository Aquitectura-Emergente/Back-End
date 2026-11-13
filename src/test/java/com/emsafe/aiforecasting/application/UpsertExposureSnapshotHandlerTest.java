package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.emsafe.aiforecasting.application.ForecastingTestContext.*;
import static org.assertj.core.api.Assertions.*;

class UpsertExposureSnapshotHandlerTest {

    private final ForecastingTestContext ctx = new ForecastingTestContext();

    private WorkerExposureSnapshot stored() {
        return ctx.snapshots.findBySessionId(session(101L)).orElseThrow();
    }

    @Test
    void createsSnapshotAndMarksEventProcessed() {
        InboxOutcome outcome = ctx.upsertSnapshot.handle(snapshotCmd("snap-1", 101L, 10L, "3600"));

        assertThat(outcome.duplicate()).isFalse();
        assertThat(stored()).satisfies(s -> {
            assertThat(s.organizationId()).isEqualTo(ORG);
            assertThat(s.zoneId()).isEqualTo(ZONE);
            assertThat(s.positionId()).isEqualTo(10L);
            assertThat(s.accumulatedIndex()).isEqualByComparingTo("3600");
            assertThat(s.plannedShiftEnd()).isEqualTo(at(16, 0));
            assertThat(s.updatedAt()).isEqualTo(at(12, 0));
        });
        assertThat(ctx.processed.isProcessed("snap-1")).isTrue();
        assertThat(ctx.assessments.all()).isEmpty();
    }

    @Test
    void updateKeepsZonePositionAndShiftEndWhenOmitted() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-1", 101L, 10L, "3600"));

        ctx.upsertSnapshot.handle(new UpsertExposureSnapshotCommand("snap-2", null, ORG, 101L, session(101L),
                null, null, new BigDecimal("4000"), new BigDecimal(LIMIT), at(8, 0), null, at(12, 30)));

        assertThat(stored()).satisfies(s -> {
            assertThat(s.accumulatedIndex()).isEqualByComparingTo("4000");
            assertThat(s.capturedAt()).isEqualTo(at(12, 30));
            assertThat(s.zoneId()).isEqualTo(ZONE);
            assertThat(s.positionId()).isEqualTo(10L);
            assertThat(s.plannedShiftEnd()).isEqualTo(at(16, 0));
        });
        assertThat(ctx.snapshots.size()).isEqualTo(1);
    }

    @Test
    void duplicateEventHasNoEffects() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-1", 101L, 10L, "3600"));

        InboxOutcome again = ctx.upsertSnapshot.handle(snapshotCmd("snap-1", 101L, 10L, "9999"));

        assertThat(again.duplicate()).isTrue();
        assertThat(stored().accumulatedIndex()).isEqualByComparingTo("3600");
    }

    @Test
    void olderCaptureDoesNotOverwriteNewerData() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-1", 101L, 10L, "3600"));

        InboxOutcome outcome = ctx.upsertSnapshot.handle(new UpsertExposureSnapshotCommand("snap-0", null, ORG,
                101L, session(101L), "Z-9", 99L, new BigDecimal("1000"), new BigDecimal(LIMIT), at(8, 0),
                at(16, 0), at(10, 0)));

        assertThat(outcome.duplicate()).isFalse();
        assertThat(stored().accumulatedIndex()).isEqualByComparingTo("3600");
        assertThat(stored().zoneId()).isEqualTo(ZONE);
        assertThat(ctx.processed.isProcessed("snap-0")).isTrue();
    }

    @Test
    void sessionBelongingToAnotherWorkerOrOrganizationIsAConflict() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-1", 101L, 10L, "3600"));

        UpsertExposureSnapshotCommand otherWorker = new UpsertExposureSnapshotCommand("snap-2", null, ORG, 102L,
                session(101L), ZONE, 10L, new BigDecimal("1"), new BigDecimal(LIMIT), at(8, 0), null, at(12, 30));
        UpsertExposureSnapshotCommand otherOrg = new UpsertExposureSnapshotCommand("snap-3", null, 2L, 101L,
                session(101L), ZONE, 10L, new BigDecimal("1"), new BigDecimal(LIMIT), at(8, 0), null, at(12, 30));

        assertThatThrownBy(() -> ctx.upsertSnapshot.handle(otherWorker))
                .isInstanceOfSatisfying(ForecastingRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ForecastingRuleException.Kind.CONFLICT));
        assertThatThrownBy(() -> ctx.upsertSnapshot.handle(otherOrg)).isInstanceOf(ForecastingRuleException.class);
        assertThat(ctx.processed.isProcessed("snap-2")).isFalse();
    }

    @Test
    void invalidDataIsAnInvalidRule() {
        UpsertExposureSnapshotCommand negative = new UpsertExposureSnapshotCommand("snap-1", null, ORG, 101L,
                session(101L), ZONE, 10L, new BigDecimal("-1"), new BigDecimal(LIMIT), at(8, 0), null, at(12, 0));
        UpsertExposureSnapshotCommand noEventId = new UpsertExposureSnapshotCommand(" ", null, ORG, 101L,
                session(101L), ZONE, 10L, new BigDecimal("1"), new BigDecimal(LIMIT), at(8, 0), null, at(12, 0));

        assertThatThrownBy(() -> ctx.upsertSnapshot.handle(negative)).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> ctx.upsertSnapshot.handle(noEventId)).isInstanceOf(ForecastingRuleException.class);
        assertThat(ctx.snapshots.size()).isZero();
    }
}
