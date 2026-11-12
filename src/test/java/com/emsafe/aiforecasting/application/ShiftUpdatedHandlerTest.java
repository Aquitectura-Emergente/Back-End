package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.commands.ShiftUpdatedCommand;
import com.emsafe.aiforecasting.domain.model.AssessmentStatus;
import com.emsafe.aiforecasting.domain.model.CloseReason;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.emsafe.aiforecasting.application.ForecastingTestContext.*;
import static org.assertj.core.api.Assertions.*;

class ShiftUpdatedHandlerTest {

    private final ForecastingTestContext ctx = new ForecastingTestContext();

    private static ShiftUpdatedCommand moveTo(String eventId, String zone, Long position) {
        return new ShiftUpdatedCommand(eventId, "corr-shift", ORG, 101L, session(101L), zone, position, null);
    }

    @Test
    void updatesSnapshotWithoutAssessmentWhenNoneIsActive() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));

        InboxOutcome outcome = ctx.shiftUpdated.handle(
                new ShiftUpdatedCommand("sh-1", null, ORG, 101L, session(101L), "Z-2", 30L, at(18, 0)));

        WorkerExposureSnapshot s = ctx.snapshots.findBySessionId(session(101L)).orElseThrow();
        assertThat(s.zoneId()).isEqualTo("Z-2");
        assertThat(s.positionId()).isEqualTo(30L);
        assertThat(s.plannedShiftEnd()).isEqualTo(at(18, 0));
        assertThat(s.accumulatedIndex()).isEqualByComparingTo("1000");
        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.assessmentId()).isNull();
        assertThat(ctx.assessments.all()).isEmpty();
        assertThat(ctx.processed.isProcessed("sh-1")).isTrue();
    }

    @Test
    void omittedPlannedShiftEndKeepsTheCurrentOne() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));

        ctx.shiftUpdated.handle(moveTo("sh-1", "Z-2", 30L));

        assertThat(ctx.snapshots.findBySessionId(session(101L)).orElseThrow().plannedShiftEnd())
                .isEqualTo(at(16, 0));
    }

    @Test
    void recalculatesTheActiveAssessment() {
        ctx.seedHighScenario();
        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));
        PreventiveAssessment before = ctx.assessments.findActiveBySessionId(session(101L)).orElseThrow();
        assertThat(before.getRecommendation().orElseThrow().type()).isEqualTo(RecommendationType.ROTATION);
        int eventsBefore = ctx.outbox.all().size();

        // Lo mueven a otra zona: ya no hay candidatos de rotación.
        ctx.clock.advance(Duration.ofMinutes(1));
        InboxOutcome outcome = ctx.shiftUpdated.handle(moveTo("sh-1", "Z-2", 30L));

        PreventiveAssessment old = ctx.assessments.findById(before.getId()).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(AssessmentStatus.CLOSED);
        assertThat(old.getCloseReason()).isEqualTo(CloseReason.SUPERSEDED);

        PreventiveAssessment now = ctx.assessments.findActiveBySessionId(session(101L)).orElseThrow();
        assertThat(now.getId()).isEqualTo(outcome.assessmentId()).isNotEqualTo(before.getId());
        assertThat(now.getTriggerEventId()).isEqualTo("sh-1");
        assertThat(now.getLimitVersionId()).isEqualTo(before.getLimitVersionId());
        assertThat(now.getCriticality()).isEqualTo(CriticalityLevel.HIGH);
        assertThat(now.getRecommendation().orElseThrow().type()).isEqualTo(RecommendationType.RESCHEDULE_TASK);
        assertThat(ctx.outbox.all()).hasSize(eventsBefore + 2);
        assertThat(ctx.outbox.all().subList(eventsBefore, eventsBefore + 2))
                .allSatisfy(e -> {
                    assertThat(e.causationId()).isEqualTo("sh-1");
                    assertThat(e.correlationId()).isEqualTo("corr-shift");
                });
    }

    @Test
    void duplicateEventHasNoEffects() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));
        ctx.shiftUpdated.handle(moveTo("sh-1", "Z-2", 30L));

        InboxOutcome again = ctx.shiftUpdated.handle(moveTo("sh-1", "Z-3", 40L));

        assertThat(again.duplicate()).isTrue();
        assertThat(ctx.snapshots.findBySessionId(session(101L)).orElseThrow().zoneId()).isEqualTo("Z-2");
    }

    @Test
    void unknownSessionIsAConflict() {
        assertThatThrownBy(() -> ctx.shiftUpdated.handle(moveTo("sh-1", "Z-2", 30L)))
                .isInstanceOfSatisfying(ForecastingRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ForecastingRuleException.Kind.CONFLICT));
        assertThat(ctx.processed.isProcessed("sh-1")).isFalse();
    }

    @Test
    void sessionOfAnotherWorkerIsAConflict() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));

        assertThatThrownBy(() -> ctx.shiftUpdated.handle(
                new ShiftUpdatedCommand("sh-1", null, ORG, 555L, session(101L), "Z-2", 30L, null)))
                .isInstanceOf(ForecastingRuleException.class);
    }
}
