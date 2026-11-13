package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.RotationCandidate;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.services.ExposureProjectionService;
import com.emsafe.aiforecasting.domain.services.RotationCandidateSelector;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.emsafe.aiforecasting.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Escenario base: el operario fuente (101, puesto 10) lleva 7200 uT·s en 4 h
 * (tasa 0.5) y le quedan 4 h (14400 s) de turno → sumaría 7200 más.
 * Límite de todos: 12000 → umbral seguro del candidato = 10800.
 */
class RotationCandidateSelectorTest {

    private final ExposureProjectionService projections = new ExposureProjectionService();
    private final RotationCandidateSelector selector =
            new RotationCandidateSelector(projections, DEFAULT_THRESHOLDS);

    private final WorkerExposureSnapshot source =
            snapshot(101L, 10L, "7200", "12000", at(8, 0), at(12, 0));
    private final ExposureProjection sourceProjection = projections.project(source, Duration.ofHours(8));

    private Optional<RotationCandidate> select(WorkerExposureSnapshot... pool) {
        return selector.selectBest(source, sourceProjection, List.of(pool));
    }

    @Test
    void picksViableCandidateWithHighestBenefit() {
        WorkerExposureSnapshot low = snapshot(200L, 20L, "1440", "12000", at(8, 0), at(12, 0)); // tasa 0.1
        WorkerExposureSnapshot mid = snapshot(300L, 30L, "2880", "12000", at(8, 0), at(12, 0)); // tasa 0.2

        RotationCandidate best = select(mid, low).orElseThrow();

        assertThat(best.snapshot().workerId()).isEqualTo(200L);
        assertThat(best.accumulationRate()).isEqualByComparingTo("0.1");
        // (0.5 − 0.1) × 14400
        assertThat(best.benefitIndex()).isEqualByComparingTo("5760");
    }

    @Test
    void tieOnBenefitIsBrokenByLowerCurrentIndex() {
        WorkerExposureSnapshot a = snapshot(200L, 20L, "1440", "12000", at(8, 0), at(12, 0));  // tasa 0.1
        WorkerExposureSnapshot d = snapshot(400L, 40L, "720", "12000", at(10, 0), at(12, 0));  // tasa 0.1, menos índice

        assertThat(select(a, d).orElseThrow().snapshot().workerId()).isEqualTo(400L);
    }

    @Test
    void tieOnBenefitAndIndexIsBrokenByLowerWorkerId() {
        WorkerExposureSnapshot a = snapshot(200L, 20L, "1440", "12000", at(8, 0), at(12, 0));
        WorkerExposureSnapshot c = snapshot(150L, 40L, "1440", "12000", at(8, 0), at(12, 0));

        assertThat(select(a, c).orElseThrow().snapshot().workerId()).isEqualTo(150L);
    }

    @Test
    void candidateWithEqualOrHigherRateIsNotViable() {
        WorkerExposureSnapshot sameRate = snapshot(200L, 20L, "1800", "12000", at(11, 0), at(12, 0)); // tasa 0.5
        WorkerExposureSnapshot higher = snapshot(300L, 30L, "2160", "12000", at(11, 0), at(12, 0));  // tasa 0.6

        assertThat(select(sameRate, higher)).isEmpty();
    }

    @Test
    void candidateThatWouldExceedSafeRatioIsNotViable() {
        // 4320 + 7200 = 11520 > 10800
        WorkerExposureSnapshot loaded = snapshot(200L, 20L, "4320", "12000", at(6, 0), at(12, 0));

        assertThat(select(loaded)).isEmpty();
    }

    @Test
    void candidateExactlyAtSafeRatioIsViable() {
        // 3600 + 7200 = 10800 = 12000 × 0.9, tasa 0.125
        WorkerExposureSnapshot edge = snapshot(200L, 20L, "3600", "12000", at(4, 0), at(12, 0));

        assertThat(select(edge)).isPresent();
    }

    @Test
    void safeRatioUsesTheCandidateLimit() {
        // Con límite propio de 9000 → umbral 8100 < 1440 + 7200
        WorkerExposureSnapshot strict = snapshot(200L, 20L, "1440", "9000", at(8, 0), at(12, 0));

        assertThat(select(strict)).isEmpty();
    }

    @Test
    void excludesSourceSamePositionOtherZoneAndOtherOrganization() {
        WorkerExposureSnapshot self = source;
        WorkerExposureSnapshot samePosition = snapshot(200L, 10L, "0", "12000", at(8, 0), at(12, 0));
        WorkerExposureSnapshot otherZone = new WorkerExposureSnapshot(ORG, 300L, "S-300", "Z-2", 30L,
                dec("0"), dec("12000"), at(8, 0), at(16, 0), at(12, 0), at(12, 0));
        WorkerExposureSnapshot otherOrg = new WorkerExposureSnapshot(2L, 400L, "S-400", ZONE, 40L,
                dec("0"), dec("12000"), at(8, 0), at(16, 0), at(12, 0), at(12, 0));
        WorkerExposureSnapshot noPosition = snapshot(500L, null, "0", "12000", at(8, 0), at(12, 0));

        assertThat(select(self, samePosition, otherZone, otherOrg, noPosition)).isEmpty();
    }

    @Test
    void candidateWithInvalidElapsedTimeIsSkipped() {
        WorkerExposureSnapshot broken = snapshot(200L, 20L, "0", "12000", at(12, 0), at(12, 0));

        assertThatCode(() -> select(broken)).doesNotThrowAnyException();
        assertThat(select(broken)).isEmpty();
    }

    @Test
    void sourceWithoutZoneOrPositionHasNoCandidates() {
        WorkerExposureSnapshot noZone = new WorkerExposureSnapshot(ORG, 101L, "S-101", null, 10L,
                dec("7200"), dec("12000"), at(8, 0), at(16, 0), at(12, 0), at(12, 0));
        WorkerExposureSnapshot candidate = snapshot(200L, 20L, "1440", "12000", at(8, 0), at(12, 0));

        assertThat(selector.selectBest(noZone, sourceProjection, List.of(candidate))).isEmpty();
    }
}
