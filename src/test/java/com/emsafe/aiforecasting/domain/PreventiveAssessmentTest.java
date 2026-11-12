package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.AssessmentStatus;
import com.emsafe.aiforecasting.domain.model.CloseReason;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;
import org.junit.jupiter.api.Test;

import static com.emsafe.aiforecasting.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class PreventiveAssessmentTest {

    private final RotationRecommendation rotation = new RotationRecommendation(RecommendationType.ROTATION,
            101L, 200L, 10L, 20L, dec("5760"), "rotar");
    private final NaturalLanguageRedaction redaction =
            new NaturalLanguageRedaction("texto", RedactionSource.FALLBACK, at(10, 1));

    private PreventiveAssessment high() {
        PreventiveAssessment a = projectedAssessment(projection("950", "1000", "1200"));
        a.assess(CriticalityLevel.HIGH);
        return a;
    }

    @Test
    void startsProjectedAndActive() {
        PreventiveAssessment a = projectedAssessment(projection("300", "1000", "600"));

        assertThat(a.getStatus()).isEqualTo(AssessmentStatus.PROJECTED);
        assertThat(a.isActive()).isTrue();
        assertThat(a.getId()).isEqualTo(ASSESSMENT_ID);
        assertThat(a.getModelVersion()).isEqualTo(MODEL);
        assertThat(a.getLimitVersionId()).isEqualTo(7L);
        assertThat(a.getTriggerEventId()).isEqualTo("evt-1");
        assertThat(a.getCriticality()).isNull();
        assertThat(a.getClosedAt()).isNull();
    }

    @Test
    void fullHappyPathProjectedAssessedRecommendedClosed() {
        PreventiveAssessment a = high();
        assertThat(a.getStatus()).isEqualTo(AssessmentStatus.ASSESSED);
        assertThat(a.getCriticality()).isEqualTo(CriticalityLevel.HIGH);

        a.recommend(rotation);
        a.attachRedaction(redaction);
        assertThat(a.getStatus()).isEqualTo(AssessmentStatus.RECOMMENDED);
        assertThat(a.getRecommendation()).contains(rotation);
        assertThat(a.getRedaction()).contains(redaction);

        a.close(CloseReason.SESSION_ENDED, at(16, 0));
        assertThat(a.getStatus()).isEqualTo(AssessmentStatus.CLOSED);
        assertThat(a.getCloseReason()).isEqualTo(CloseReason.SESSION_ENDED);
        assertThat(a.getClosedAt()).isEqualTo(at(16, 0));
        assertThat(a.isActive()).isFalse();
    }

    @Test
    void assessedCanCloseWithoutRecommendation() {
        PreventiveAssessment a = projectedAssessment(projection("300", "1000", "600"));
        a.assess(CriticalityLevel.LOW);
        a.attachRedaction(redaction);

        a.supersede(at(11, 0));

        assertThat(a.getStatus()).isEqualTo(AssessmentStatus.CLOSED);
        assertThat(a.getCloseReason()).isEqualTo(CloseReason.SUPERSEDED);
        assertThat(a.getRecommendation()).isEmpty();
    }

    @Test
    void rejectsInvalidTransitions() {
        PreventiveAssessment projected = projectedAssessment(projection("950", "1000", "1200"));
        assertThatThrownBy(() -> projected.recommend(rotation)).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> projected.close(CloseReason.SUPERSEDED, at(11, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> projected.attachRedaction(redaction)).isInstanceOf(ForecastingRuleException.class);

        PreventiveAssessment assessed = high();
        assertThatThrownBy(() -> assessed.assess(CriticalityLevel.LOW)).isInstanceOf(ForecastingRuleException.class);

        assessed.recommend(rotation);
        assertThatThrownBy(() -> assessed.recommend(rotation)).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void invalidTransitionIsAnInvalidRule() {
        PreventiveAssessment projected = projectedAssessment(projection("950", "1000", "1200"));

        assertThatThrownBy(() -> projected.recommend(rotation))
                .isInstanceOfSatisfying(ForecastingRuleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(ForecastingRuleException.Kind.INVALID_RULE));
    }

    @Test
    void recommendationOnlyForHighOrCritical() {
        PreventiveAssessment a = projectedAssessment(projection("300", "1000", "900"));
        a.assess(CriticalityLevel.MODERATE);

        assertThatThrownBy(() -> a.recommend(rotation)).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void recommendationMustTargetTheAssessedWorker() {
        PreventiveAssessment a = high();
        RotationRecommendation forOther = new RotationRecommendation(RecommendationType.ROTATION,
                999L, 200L, 10L, 20L, dec("1"), "rotar");

        assertThatThrownBy(() -> a.recommend(forOther)).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void closedAssessmentIsImmutable() {
        PreventiveAssessment a = high();
        a.close(CloseReason.SUPERSEDED, at(11, 0));

        assertThatThrownBy(() -> a.assess(CriticalityLevel.LOW)).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> a.recommend(rotation)).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> a.attachRedaction(redaction)).isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> a.close(CloseReason.SESSION_ENDED, at(12, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> a.supersede(at(12, 0))).isInstanceOf(ForecastingRuleException.class);
        assertThat(a.getClosedAt()).isEqualTo(at(11, 0));
        assertThat(a.getCloseReason()).isEqualTo(CloseReason.SUPERSEDED);
    }

    @Test
    void cannotCloseBeforeAssessment() {
        PreventiveAssessment a = high();

        assertThatThrownBy(() -> a.close(CloseReason.SUPERSEDED, at(9, 0)))
                .isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void rejectsInvalidCreationData() {
        assertThatThrownBy(() -> PreventiveAssessment.project("not-a-uuid", ORG, 101L, "S-101",
                projection("1", "10", "2"), MODEL, 7L, "evt-1", at(10, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> PreventiveAssessment.project(ASSESSMENT_ID, null, 101L, "S-101",
                projection("1", "10", "2"), MODEL, 7L, "evt-1", at(10, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> PreventiveAssessment.project(ASSESSMENT_ID, ORG, 101L, " ",
                projection("1", "10", "2"), MODEL, 7L, "evt-1", at(10, 0)))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> PreventiveAssessment.project(ASSESSMENT_ID, ORG, 101L, "S-101",
                null, MODEL, 7L, "evt-1", at(10, 0)))
                .isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void restoresPersistedStateAndKeepsRules() {
        PreventiveAssessment closed = PreventiveAssessment.restore(ASSESSMENT_ID, ORG, 101L, "S-101",
                projection("950", "1000", "1200"), CriticalityLevel.HIGH, rotation, redaction, MODEL, 7L,
                AssessmentStatus.CLOSED, CloseReason.SUPERSEDED, "evt-1", at(10, 0), at(11, 0));

        assertThat(closed.isActive()).isFalse();
        assertThatThrownBy(() -> closed.attachRedaction(redaction)).isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void restoreRejectsInconsistentState() {
        assertThatThrownBy(() -> PreventiveAssessment.restore(ASSESSMENT_ID, ORG, 101L, "S-101",
                projection("950", "1000", "1200"), CriticalityLevel.HIGH, null, null, MODEL, 7L,
                AssessmentStatus.CLOSED, null, "evt-1", at(10, 0), null))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> PreventiveAssessment.restore(ASSESSMENT_ID, ORG, 101L, "S-101",
                projection("950", "1000", "1200"), CriticalityLevel.HIGH, null, null, MODEL, 7L,
                AssessmentStatus.RECOMMENDED, null, "evt-1", at(10, 0), null))
                .isInstanceOf(ForecastingRuleException.class);
        assertThatThrownBy(() -> PreventiveAssessment.restore(ASSESSMENT_ID, ORG, 101L, "S-101",
                projection("950", "1000", "1200"), null, null, null, MODEL, 7L,
                AssessmentStatus.ASSESSED, null, "evt-1", at(10, 0), null))
                .isInstanceOf(ForecastingRuleException.class);
    }

    @Test
    void statusTransitionTable() {
        assertThat(AssessmentStatus.PROJECTED.canTransitionTo(AssessmentStatus.ASSESSED)).isTrue();
        assertThat(AssessmentStatus.ASSESSED.canTransitionTo(AssessmentStatus.RECOMMENDED)).isTrue();
        assertThat(AssessmentStatus.ASSESSED.canTransitionTo(AssessmentStatus.CLOSED)).isTrue();
        assertThat(AssessmentStatus.RECOMMENDED.canTransitionTo(AssessmentStatus.CLOSED)).isTrue();

        assertThat(AssessmentStatus.PROJECTED.canTransitionTo(AssessmentStatus.RECOMMENDED)).isFalse();
        assertThat(AssessmentStatus.PROJECTED.canTransitionTo(AssessmentStatus.CLOSED)).isFalse();
        assertThat(AssessmentStatus.RECOMMENDED.canTransitionTo(AssessmentStatus.ASSESSED)).isFalse();
        for (AssessmentStatus target : AssessmentStatus.values()) {
            assertThat(AssessmentStatus.CLOSED.canTransitionTo(target)).isFalse();
        }
    }
}
