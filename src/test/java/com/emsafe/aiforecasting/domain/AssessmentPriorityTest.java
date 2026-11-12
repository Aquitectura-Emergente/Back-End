package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.AssessmentPriority;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.emsafe.aiforecasting.domain.DomainFixtures.*;
import static org.assertj.core.api.Assertions.*;

class AssessmentPriorityTest {

    private static PreventiveAssessment assessment(long workerId, CriticalityLevel level, Instant exceedance) {
        ExposureProjection p = new ExposureProjection(dec("1"), dec("10"), dec("0.1"), dec("2"), dec("9"),
                dec("0.1"), dec("0.2"), exceedance, at(16, 0));
        PreventiveAssessment a = PreventiveAssessment.project(UUID.randomUUID().toString(), ORG, workerId,
                "S-" + workerId, p, MODEL, null, "evt", at(10, 0));
        a.assess(level);
        return a;
    }

    @Test
    void ordersByCriticalityDescThenExceedanceAscWithNullsLast() {
        PreventiveAssessment low = assessment(1L, CriticalityLevel.LOW, null);
        PreventiveAssessment highLate = assessment(2L, CriticalityLevel.HIGH, at(15, 0));
        PreventiveAssessment highSoon = assessment(3L, CriticalityLevel.HIGH, at(13, 0));
        PreventiveAssessment highNone = assessment(4L, CriticalityLevel.HIGH, null);
        PreventiveAssessment critical = assessment(5L, CriticalityLevel.CRITICAL, at(16, 0));
        PreventiveAssessment moderate = assessment(6L, CriticalityLevel.MODERATE, null);

        List<PreventiveAssessment> list =
                new ArrayList<>(List.of(low, highLate, highNone, moderate, critical, highSoon));
        list.sort(AssessmentPriority.MOST_URGENT_FIRST);

        assertThat(list).extracting(PreventiveAssessment::getWorkerId).containsExactly(5L, 3L, 2L, 4L, 6L, 1L);
    }

    @Test
    void tieIsBrokenByWorkerId() {
        PreventiveAssessment b = assessment(9L, CriticalityLevel.HIGH, at(13, 0));
        PreventiveAssessment a = assessment(7L, CriticalityLevel.HIGH, at(13, 0));

        List<PreventiveAssessment> list = new ArrayList<>(List.of(b, a));
        list.sort(AssessmentPriority.MOST_URGENT_FIRST);

        assertThat(list).extracting(PreventiveAssessment::getWorkerId).containsExactly(7L, 9L);
    }

    @Test
    void outboxPayloadAcceptsNullValuesAndIsImmutable() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectedExceedanceAt", null);
        OutboxEvent e = new OutboxEvent("e-1", "RotationSuggested", 1, ORG, "a-1", at(10, 0),
                "c-1", "x-1", payload, null);

        assertThat(e.payload()).containsEntry("projectedExceedanceAt", null);
        assertThat(e.isPending()).isTrue();
        assertThatThrownBy(() -> e.payload().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(e.markPublished(at(11, 0)).isPending()).isFalse();
    }
}
