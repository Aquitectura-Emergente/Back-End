package com.emsafe.personaldosimetry.application;

import com.emsafe.personaldosimetry.domain.model.ExposureSession;
import com.emsafe.personaldosimetry.domain.model.OccupationalLimit;
import com.emsafe.personaldosimetry.infrastructure.persistence.ExposureSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** La consulta pública que usa Occupational Health (2026-11-22). */
class ExposureQueryFacadeTest {

    private static final Instant START = Instant.parse("2026-11-22T12:00:00Z");

    private final ExposureSessionRepository sessions = mock(ExposureSessionRepository.class);
    private final ExposureQueryFacade facade = new ExposureQueryFacade(sessions);

    @Test
    void returnsTheOpenSessionInUtSeconds() {
        var limit = new OccupationalLimit(1L, 4L, 1, new BigDecimal("1000"), new BigDecimal("0.8"),
                START, "DEMO", 1L, START);
        ReflectionTestUtils.setField(limit, "id", 10L);
        var session = ExposureSession.open(1L, 2L, 3L, limit, START);
        session.apply(new BigDecimal("250.5"), 1_000_000, START.plusSeconds(1));
        when(sessions.findFirstByOrganizationIdAndWorkerIdAndStateOrderByStartedAtDesc(
                1L, 2L, ExposureSession.State.OPEN)).thenReturn(Optional.of(session));

        var exposure = facade.currentExposure(1L, 2L).orElseThrow();

        assertThat(exposure.accumulatedIndex()).isEqualByComparingTo("250.5");
        assertThat(exposure.limitIndex()).isEqualByComparingTo("1000");
        assertThat(exposure.unit()).isEqualTo("uT*s");
        assertThat(exposure.methodId()).isEqualTo("MAGNETIC_TIME_INDEX_V1");
        assertThat(exposure.jobPositionId()).isEqualTo(4L);
    }

    @Test
    void withoutAnOpenSessionTheExposureIsUnknown() {
        when(sessions.findFirstByOrganizationIdAndWorkerIdAndStateOrderByStartedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThat(facade.currentExposure(1L, 2L)).isEmpty();
        assertThat(facade.currentExposure(null, 2L)).isEmpty();
        assertThat(facade.currentExposure(1L, null)).isEmpty();
    }
}
