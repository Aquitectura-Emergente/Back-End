package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.commands.ExposureWarningRaisedCommand;
import com.emsafe.aiforecasting.application.fakes.StubNaturalLanguagePort;
import com.emsafe.aiforecasting.domain.model.AssessmentStatus;
import com.emsafe.aiforecasting.domain.model.CloseReason;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static com.emsafe.aiforecasting.application.ForecastingTestContext.*;
import static org.assertj.core.api.Assertions.*;

class TriggerAssessmentHandlerTest {

    private final ForecastingTestContext ctx = new ForecastingTestContext();

    private PreventiveAssessment only() {
        assertThat(ctx.assessments.all()).hasSize(1);
        return ctx.assessments.all().get(0);
    }

    @Test
    void lowFlowHasNoRecommendationAndNoEvents() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));

        InboxOutcome outcome = ctx.trigger.handle(warningCmd("w-1", 101L, "2000"));

        PreventiveAssessment a = only();
        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.assessmentId()).isEqualTo(a.getId());
        assertThat(a.getStatus()).isEqualTo(AssessmentStatus.ASSESSED);
        assertThat(a.getCriticality()).isEqualTo(CriticalityLevel.LOW);
        assertThat(a.getRecommendation()).isEmpty();
        assertThat(a.getRedaction()).isPresent();
        assertThat(a.getLimitVersionId()).isEqualTo(7L);
        assertThat(a.getTriggerEventId()).isEqualTo("w-1");
        assertThat(a.getModelVersion().code()).isEqualTo("LINEAR_RATE_V1");
        assertThat(a.getAssessedAt()).isEqualTo(at(12, 0));
        assertThat(ctx.outbox.all()).isEmpty();
        assertThat(ctx.processed.isProcessed("w-1")).isTrue();
    }

    @Test
    void highFlowWithRotationEmitsBothEvents() {
        ctx.seedHighScenario();

        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));

        PreventiveAssessment a = only();
        assertThat(a.getStatus()).isEqualTo(AssessmentStatus.RECOMMENDED);
        assertThat(a.getCriticality()).isEqualTo(CriticalityLevel.HIGH);
        assertThat(a.getRecommendation()).hasValueSatisfying(r -> {
            assertThat(r.type()).isEqualTo(RecommendationType.ROTATION);
            assertThat(r.targetWorkerId()).isEqualTo(200L);
            assertThat(r.estimatedBenefitIndex()).isEqualByComparingTo("5760");
        });
        assertThat(a.getProjection().estimatedExceedanceAt()).isEqualTo(at(14, 40));

        assertThat(ctx.outbox.all()).extracting(OutboxEvent::eventType)
                .containsExactly(ForecastingEventFactory.ROTATION_SUGGESTED,
                        ForecastingEventFactory.PREDICTIVE_RISK_DETECTED);
    }

    @Test
    void rotationSuggestedMatchesOccupationalHealthInboxContract() {
        ctx.seedHighScenario();
        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));
        PreventiveAssessment a = only();

        OutboxEvent e = ctx.outbox.ofType(ForecastingEventFactory.ROTATION_SUGGESTED).get(0);

        assertThat(e.schemaVersion()).isEqualTo(1);
        assertThat(e.organizationId()).isEqualTo(ORG);
        assertThat(e.aggregateId()).isEqualTo(a.getId());
        assertThat(e.occurredAt()).isEqualTo(at(12, 0));
        assertThat(e.causationId()).isEqualTo("w-1");
        assertThat(e.correlationId()).isEqualTo("corr-w-1");
        assertThat(e.isPending()).isTrue();
        // Cuerpo plano igual a RotationSuggestedInput de Occupational Health.
        assertThat(e.payload()).containsOnlyKeys("organizationId", "workerId", "suggestionId",
                "candidateWorkerIds", "projectedExceedanceAt", "criticality", "rationale");
        assertThat(e.payload())
                .containsEntry("organizationId", ORG)
                .containsEntry("workerId", 101L)
                .containsEntry("suggestionId", a.getId())
                .containsEntry("candidateWorkerIds", List.of(200L))
                // LocalDateTime en UTC, sin zona
                .containsEntry("projectedExceedanceAt", "2026-11-10T14:40:00")
                .containsEntry("criticality", "HIGH")
                .containsEntry("rationale", a.getRedaction().orElseThrow().text());
    }

    @Test
    void predictiveRiskDetectedPayload() {
        ctx.seedHighScenario();
        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));
        PreventiveAssessment a = only();

        OutboxEvent e = ctx.outbox.ofType(ForecastingEventFactory.PREDICTIVE_RISK_DETECTED).get(0);

        assertThat(e.causationId()).isEqualTo("w-1");
        assertThat(e.payload())
                .containsEntry("organizationId", ORG)
                .containsEntry("assessmentId", a.getId())
                .containsEntry("workerId", 101L)
                .containsEntry("criticality", "HIGH")
                .containsEntry("estimatedExceedanceAt", "2026-11-10T14:40:00Z")
                .containsEntry("isProjection", true);
        assertThat((BigDecimal) e.payload().get("projectedIndex")).isEqualByComparingTo("14400");
        assertThat((BigDecimal) e.payload().get("limitIndex")).isEqualByComparingTo("12000");
        assertThat(ctx.outbox.all()).extracting(OutboxEvent::eventId).doesNotHaveDuplicates();
    }

    @Test
    void criticalWithoutCandidateReschedulesTask() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "10000"));

        ctx.trigger.handle(warningCmd("w-1", 101L, "11000"));

        PreventiveAssessment a = only();
        assertThat(a.getCriticality()).isEqualTo(CriticalityLevel.CRITICAL);
        assertThat(a.getRecommendation()).hasValueSatisfying(r -> {
            assertThat(r.type()).isEqualTo(RecommendationType.RESCHEDULE_TASK);
            assertThat(r.targetWorkerId()).isNull();
        });
        OutboxEvent rotation = ctx.outbox.ofType(ForecastingEventFactory.ROTATION_SUGGESTED).get(0);
        assertThat(rotation.payload()).containsEntry("candidateWorkerIds", List.of())
                .containsEntry("criticality", "CRITICAL");
        assertThat(ctx.outbox.ofType(ForecastingEventFactory.PREDICTIVE_RISK_DETECTED)).hasSize(1);
    }

    @Test
    void llmSuccessIsUsed() {
        ctx.seedHighScenario();

        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));

        assertThat(only().getRedaction()).hasValueSatisfying(r -> {
            assertThat(r.generatedBy()).isEqualTo(RedactionSource.LLM);
            assertThat(r.text()).isEqualTo(StubNaturalLanguagePort.LLM_TEXT);
        });
    }

    @Test
    void llmFailureFallsBackAndAssessmentCompletes() {
        ctx.seedHighScenario();
        ctx.llm.fail();

        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));

        PreventiveAssessment a = only();
        assertThat(ctx.llm.calls()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(AssessmentStatus.RECOMMENDED);
        assertThat(a.getRedaction()).hasValueSatisfying(r -> {
            assertThat(r.generatedBy()).isEqualTo(RedactionSource.FALLBACK);
            assertThat(r.text()).contains("101").contains("120.00 %");
        });
        assertThat(ctx.outbox.ofType(ForecastingEventFactory.ROTATION_SUGGESTED).get(0).payload())
                .containsEntry("rationale", a.getRedaction().orElseThrow().text());
    }

    @Test
    void llmReturningNothingFallsBack() {
        ctx.seedHighScenario();
        ctx.llm.returnNull();

        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));

        assertThat(only().getRedaction()).hasValueSatisfying(
                r -> assertThat(r.generatedBy()).isEqualTo(RedactionSource.FALLBACK));
    }

    @Test
    void duplicateEventHasNoEffects() {
        ctx.seedHighScenario();
        InboxOutcome first = ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));
        int events = ctx.outbox.all().size();

        ctx.clock.advance(Duration.ofMinutes(5));
        InboxOutcome second = ctx.trigger.handle(warningCmd("w-1", 101L, "9000"));

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.assessmentId()).isNull();
        assertThat(ctx.assessments.all()).hasSize(1);
        assertThat(only().isActive()).isTrue();
        assertThat(ctx.outbox.all()).hasSize(events);
        assertThat(ctx.snapshots.findBySessionId(session(101L)).orElseThrow().accumulatedIndex())
                .isEqualByComparingTo("7200");
        assertThat(ctx.llm.calls()).isEqualTo(1);
    }

    @Test
    void secondTriggerForSameSessionSupersedesThePrevious() {
        ctx.seedHighScenario();
        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));
        String firstId = only().getId();

        ctx.clock.advance(Duration.ofMinutes(10));
        ExposureWarningRaisedCommand later = new ExposureWarningRaisedCommand("w-2", null, ORG, session(101L), 101L,
                new BigDecimal("7500"), new BigDecimal(LIMIT), 7L, at(12, 10),
                ExposureWarningRaisedCommand.SUPPORTED_METHOD, ExposureWarningRaisedCommand.SUPPORTED_UNIT);
        InboxOutcome outcome = ctx.trigger.handle(later);

        PreventiveAssessment first = ctx.assessments.findById(firstId).orElseThrow();
        assertThat(first.getStatus()).isEqualTo(AssessmentStatus.CLOSED);
        assertThat(first.getCloseReason()).isEqualTo(CloseReason.SUPERSEDED);
        assertThat(first.getClosedAt()).isEqualTo(at(12, 10));

        PreventiveAssessment active = ctx.assessments.findActiveBySessionId(session(101L)).orElseThrow();
        assertThat(active.getId()).isEqualTo(outcome.assessmentId()).isNotEqualTo(firstId);
        assertThat(active.getTriggerEventId()).isEqualTo("w-2");
        assertThat(ctx.assessments.all().stream().filter(PreventiveAssessment::isActive)).hasSize(1);
        // Sin correlationId de entrada, la cadena se correlaciona con el propio evento.
        assertThat(ctx.outbox.all()).filteredOn(e -> "w-2".equals(e.causationId()))
                .isNotEmpty().allSatisfy(e -> assertThat(e.correlationId()).isEqualTo("w-2"));
    }

    @Test
    void warningUpdatesTheSnapshot() {
        ctx.seedHighScenario();

        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));

        assertThat(ctx.snapshots.findBySessionId(session(101L)).orElseThrow())
                .satisfies(s -> {
                    assertThat(s.accumulatedIndex()).isEqualByComparingTo("7200");
                    assertThat(s.positionId()).isEqualTo(10L);
                    assertThat(s.zoneId()).isEqualTo(ZONE);
                });
    }

    @Test
    void staleWarningDoesNotOverwriteNewerSnapshot() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "2000"));
        ExposureWarningRaisedCommand stale = new ExposureWarningRaisedCommand("w-old", null, ORG, session(101L),
                101L, new BigDecimal("1500"), new BigDecimal(LIMIT), 7L, at(11, 0),
                ExposureWarningRaisedCommand.SUPPORTED_METHOD, ExposureWarningRaisedCommand.SUPPORTED_UNIT);

        ctx.trigger.handle(stale);

        assertThat(ctx.snapshots.findBySessionId(session(101L)).orElseThrow().accumulatedIndex())
                .isEqualByComparingTo("2000");
        assertThat(only().getProjection().currentIndex()).isEqualByComparingTo("2000");
    }

    @Test
    void unknownSessionIsAConflictAndIsNotMarkedProcessed() {
        assertThatThrownBy(() -> ctx.trigger.handle(warningCmd("w-1", 101L, "7200")))
                .isInstanceOfSatisfying(ForecastingRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ForecastingRuleException.Kind.CONFLICT));
        assertThat(ctx.processed.isProcessed("w-1")).isFalse();
        assertThat(ctx.assessments.all()).isEmpty();
    }

    @Test
    void sessionOfAnotherWorkerIsAConflict() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));
        ExposureWarningRaisedCommand wrong = new ExposureWarningRaisedCommand("w-1", null, ORG, session(101L), 999L,
                new BigDecimal("2000"), new BigDecimal(LIMIT), 7L, at(12, 0),
                ExposureWarningRaisedCommand.SUPPORTED_METHOD, ExposureWarningRaisedCommand.SUPPORTED_UNIT);

        assertThatThrownBy(() -> ctx.trigger.handle(wrong))
                .isInstanceOfSatisfying(ForecastingRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ForecastingRuleException.Kind.CONFLICT));
    }

    @Test
    void unsupportedMethodOrUnitIsAnInvalidRule() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));
        ExposureWarningRaisedCommand badMethod = new ExposureWarningRaisedCommand("w-1", null, ORG, session(101L),
                101L, new BigDecimal("2000"), new BigDecimal(LIMIT), 7L, at(12, 0), "OTHER", "uT*s");
        ExposureWarningRaisedCommand badUnit = new ExposureWarningRaisedCommand("w-2", null, ORG, session(101L),
                101L, new BigDecimal("2000"), new BigDecimal(LIMIT), 7L, at(12, 0),
                ExposureWarningRaisedCommand.SUPPORTED_METHOD, "mSv");

        assertThatThrownBy(() -> ctx.trigger.handle(badMethod))
                .isInstanceOfSatisfying(ForecastingRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ForecastingRuleException.Kind.INVALID_RULE));
        assertThatThrownBy(() -> ctx.trigger.handle(badUnit)).isInstanceOf(ForecastingRuleException.class);
        assertThat(ctx.assessments.all()).isEmpty();
    }

    @Test
    void invalidProjectionIsAnInvalidRule() {
        // Captura igual al inicio de sesión → elapsed = 0 → 422
        ctx.upsertSnapshot.handle(new com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand(
                "snap-101", null, ORG, 101L, session(101L), ZONE, 10L, BigDecimal.ZERO, new BigDecimal(LIMIT),
                at(12, 0), at(16, 0), at(12, 0)));

        assertThatThrownBy(() -> ctx.trigger.handle(warningCmd("w-1", 101L, "0")))
                .isInstanceOfSatisfying(ForecastingRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ForecastingRuleException.Kind.INVALID_RULE));
    }
}
