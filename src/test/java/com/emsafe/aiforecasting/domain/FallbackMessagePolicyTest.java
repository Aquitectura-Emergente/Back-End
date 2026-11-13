package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;
import com.emsafe.aiforecasting.domain.services.ExposureProjectionService;
import com.emsafe.aiforecasting.domain.services.FallbackMessagePolicy;
import org.junit.jupiter.api.Test;

import static com.emsafe.aiforecasting.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class FallbackMessagePolicyTest {

    private final FallbackMessagePolicy policy = new FallbackMessagePolicy();
    // consumido 30 %, proyectado 120 %, excedencia 14:40 UTC
    private final ExposureProjection projection =
            new ExposureProjectionService().project(dec("3600"), dec("12000"), at(8, 0), at(10, 0), at(16, 0));

    @Test
    void rotationTextContainsAllFigures() {
        PreventiveAssessment a = projectedAssessment(projection);
        a.assess(CriticalityLevel.HIGH);
        a.recommend(new RotationRecommendation(RecommendationType.ROTATION, 101L, 200L, 10L, 20L,
                dec("5760"), "rotar"));

        NaturalLanguageRedaction r = policy.redact(a, at(10, 1));

        assertThat(r.generatedBy()).isEqualTo(RedactionSource.FALLBACK);
        assertThat(r.generatedAt()).isEqualTo(at(10, 1));
        assertThat(r.text())
                .contains("101")
                .contains("S-101")
                .contains("HIGH")
                .contains("30.00 %")
                .contains("120.00 %")
                .contains("2026-11-10 14:40 UTC")
                .contains("16:00 UTC")
                .contains("200")
                .contains("5760.000000");
    }

    @Test
    void rescheduleTextHasNoTargetWorker() {
        PreventiveAssessment a = projectedAssessment(projection);
        a.assess(CriticalityLevel.CRITICAL);
        a.recommend(new RotationRecommendation(RecommendationType.RESCHEDULE_TASK, 101L, null, 10L, null,
                dec("10800"), "reprogramar"));

        String text = policy.redact(a, at(10, 1)).text();

        assertThat(text).contains("CRITICAL").contains("reprogramar").doesNotContain("null");
    }

    @Test
    void lowWithoutExceedanceSaysSo() {
        ExposureProjection safe =
                new ExposureProjectionService().project(dec("3600"), dec("20000"), at(8, 0), at(10, 0), at(16, 0));
        PreventiveAssessment a = projectedAssessment(safe);
        a.assess(CriticalityLevel.LOW);

        String text = policy.redact(a, at(10, 1)).text();

        assertThat(text).contains("LOW").contains("18.00 %").contains("72.00 %")
                .contains("sin excedencia proyectada").doesNotContain("null");
    }

    @Test
    void requiresAnAssessedAssessment() {
        PreventiveAssessment projected = projectedAssessment(projection);

        assertThatThrownBy(() -> policy.redact(projected, at(10, 1)))
                .isInstanceOf(com.emsafe.aiforecasting.domain.model.ForecastingRuleException.class);
    }
}
