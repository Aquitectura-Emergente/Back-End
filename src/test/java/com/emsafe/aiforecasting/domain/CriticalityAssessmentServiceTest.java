package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ForecastingThresholds;
import com.emsafe.aiforecasting.domain.services.CriticalityAssessmentService;
import org.junit.jupiter.api.Test;

import static com.emsafe.aiforecasting.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

/** Límite 1000 en todos los casos → 10 % de margen = 100. */
class CriticalityAssessmentServiceTest {

    private final CriticalityAssessmentService service = new CriticalityAssessmentService(DEFAULT_THRESHOLDS);

    @Test
    void criticalWhenMarginBelowTenPercentAndNoViableCandidate() {
        assertThat(service.assess(projection("950", "1000", "1200"), false)).isEqualTo(CriticalityLevel.CRITICAL);
    }

    @Test
    void highWhenMarginBelowTenPercentButCandidateExists() {
        assertThat(service.assess(projection("950", "1000", "1200"), true)).isEqualTo(CriticalityLevel.HIGH);
    }

    @Test
    void highWhenProjectionExceedsLimitWithComfortableMargin() {
        assertThat(service.assess(projection("300", "1000", "1100"), false)).isEqualTo(CriticalityLevel.HIGH);
        assertThat(service.assess(projection("300", "1000", "1100"), true)).isEqualTo(CriticalityLevel.HIGH);
    }

    @Test
    void moderateWhenProjectedRatioReachesNinetyPercent() {
        assertThat(service.assess(projection("300", "1000", "900"), false)).isEqualTo(CriticalityLevel.MODERATE);
        // Proyección justo en el límite: no es > 1, sigue siendo MODERATE.
        assertThat(service.assess(projection("300", "1000", "1000"), false)).isEqualTo(CriticalityLevel.MODERATE);
    }

    @Test
    void lowOtherwise() {
        // 899.999 / 1000 = 0.899999 a escala 6 (899.999999 redondearía a 0.900000 → MODERATE).
        assertThat(service.assess(projection("300", "1000", "899.999"), false)).isEqualTo(CriticalityLevel.LOW);
    }

    @Test
    void marginExactlyAtTenPercentIsNotCritical() {
        assertThat(service.assess(projection("900", "1000", "900"), false)).isEqualTo(CriticalityLevel.MODERATE);
    }

    @Test
    void alreadyExceededWithoutCandidateIsCritical() {
        assertThat(service.assess(projection("1100", "1000", "1300"), false)).isEqualTo(CriticalityLevel.CRITICAL);
    }

    @Test
    void thresholdsAreConfigurable() {
        CriticalityAssessmentService strict = new CriticalityAssessmentService(
                new ForecastingThresholds(dec("0.20"), dec("0.80"), dec("0.90")));

        assertThat(strict.assess(projection("850", "1000", "860"), false)).isEqualTo(CriticalityLevel.CRITICAL);
        assertThat(strict.assess(projection("300", "1000", "800"), false)).isEqualTo(CriticalityLevel.MODERATE);
    }

    @Test
    void levelsAreOrderedBySeverity() {
        assertThat(CriticalityLevel.CRITICAL.isMoreSevereThan(CriticalityLevel.HIGH)).isTrue();
        assertThat(CriticalityLevel.LOW.isMoreSevereThan(CriticalityLevel.MODERATE)).isFalse();
        assertThat(CriticalityLevel.HIGH.requiresRecommendation()).isTrue();
        assertThat(CriticalityLevel.CRITICAL.requiresRecommendation()).isTrue();
        assertThat(CriticalityLevel.MODERATE.requiresRecommendation()).isFalse();
        assertThat(CriticalityLevel.LOW.requiresRecommendation()).isFalse();
    }
}
