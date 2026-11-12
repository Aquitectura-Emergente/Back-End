package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RotationCandidate;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.services.RotationRecommendationService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.emsafe.aiforecasting.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class RotationRecommendationServiceTest {

    private final RotationRecommendationService service = new RotationRecommendationService();

    private final WorkerExposureSnapshot source = snapshot(101L, 10L, "950", "1000", at(8, 0), at(12, 0));
    private final ExposureProjection projection = projection("950", "1000", "1200");
    private final RotationCandidate candidate = new RotationCandidate(
            snapshot(200L, 20L, "100", "1000", at(8, 0), at(12, 0)), dec("0.1"), dec("5760"));

    @Test
    void noRecommendationForLowOrModerate() {
        assertThat(service.recommend(CriticalityLevel.LOW, source, projection, Optional.of(candidate))).isEmpty();
        assertThat(service.recommend(CriticalityLevel.MODERATE, source, projection, Optional.of(candidate))).isEmpty();
    }

    @Test
    void rotationWhenViableCandidateExists() {
        RotationRecommendation r = service.recommend(CriticalityLevel.HIGH, source, projection, Optional.of(candidate))
                .orElseThrow();

        assertThat(r.type()).isEqualTo(RecommendationType.ROTATION);
        assertThat(r.sourceWorkerId()).isEqualTo(101L);
        assertThat(r.sourcePositionId()).isEqualTo(10L);
        assertThat(r.targetWorkerId()).isEqualTo(200L);
        assertThat(r.targetPositionId()).isEqualTo(20L);
        assertThat(r.estimatedBenefitIndex()).isEqualByComparingTo("5760");
        assertThat(r.reason()).contains("200").contains("20").contains("10");
    }

    @Test
    void rescheduleWithoutTargetWhenCriticalAndNoCandidate() {
        RotationRecommendation r = service.recommend(CriticalityLevel.CRITICAL, source, projection, Optional.empty())
                .orElseThrow();

        assertThat(r.type()).isEqualTo(RecommendationType.RESCHEDULE_TASK);
        assertThat(r.targetWorkerId()).isNull();
        assertThat(r.targetPositionId()).isNull();
        // Reprogramar evita lo que falta acumular: 1200 − 950.
        assertThat(r.estimatedBenefitIndex()).isEqualByComparingTo("250");
        assertThat(r.reason()).isNotBlank();
    }

    @Test
    void highWithoutCandidateAlsoReschedules() {
        RotationRecommendation r = service.recommend(CriticalityLevel.HIGH, source, projection, Optional.empty())
                .orElseThrow();

        assertThat(r.type()).isEqualTo(RecommendationType.RESCHEDULE_TASK);
    }
}
