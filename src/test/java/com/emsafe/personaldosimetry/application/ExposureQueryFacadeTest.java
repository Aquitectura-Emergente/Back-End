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

    // ─── closedSessions (2026-11-25, analítica de AI Forecasting) ────────────

    private static ExposureSession closedSession(Long workerId, String accumulated, Instant end) {
        var limit = new OccupationalLimit(1L, 4L, 1, new BigDecimal("1000"), new BigDecimal("0.8"),
                START, "DEMO", 1L, START);
        ReflectionTestUtils.setField(limit, "id", 10L);
        var session = ExposureSession.open(1L, workerId, 3L, limit, START);
        // cubre la jornada completa: [START, end)
        long micros = java.time.Duration.between(START, end).toNanos() / 1_000;
        session.apply(new BigDecimal(accumulated), micros, end);
        session.requestClose(end, end);
        session.finalizeSession(end.plusSeconds(60), true);
        ReflectionTestUtils.setField(session, "id", "S-" + workerId);
        return session;
    }

    @Test
    void closedSessionsMapsTheFrozenFiguresOfEachSession() {
        Instant from = START.minusSeconds(3600);
        Instant to = START.plusSeconds(86_400);
        Instant end = START.plusSeconds(8 * 3600);
        when(sessions.findEndedBetween(1L, ExposureSession.State.CLOSED, from, to))
                .thenReturn(java.util.List.of(closedSession(2L, "900.5", end)));

        var closed = facade.closedSessions(1L, from, to);

        assertThat(closed).hasSize(1);
        var s = closed.get(0);
        assertThat(s.sessionId()).isEqualTo("S-2");
        assertThat(s.workerId()).isEqualTo(2L);
        assertThat(s.jobPositionId()).isEqualTo(4L);
        assertThat(s.accumulatedIndex()).isEqualByComparingTo("900.5");
        assertThat(s.limitIndex()).isEqualByComparingTo("1000");
        assertThat(s.warningRatio()).isEqualByComparingTo("0.8");
        assertThat(s.warningRaised()).isTrue();
        assertThat(s.limitExceeded()).isFalse();
        assertThat(s.startedAt()).isEqualTo(START);
        assertThat(s.endedAt()).isEqualTo(end);
        assertThat(s.finalizedAt()).isEqualTo(end.plusSeconds(60));
        assertThat(s.coveragePercentage()).isEqualByComparingTo("100.00");
        assertThat(s.coverageComplete()).isTrue();
        assertThat(s.unit()).isEqualTo("uT*s");
        assertThat(s.methodId()).isEqualTo("MAGNETIC_TIME_INDEX_V1");
    }

    @Test
    void closedSessionsWithInvalidArgumentsIsEmptyWithoutQuerying() {
        Instant t = START;

        assertThat(facade.closedSessions(null, t, t.plusSeconds(1))).isEmpty();
        assertThat(facade.closedSessions(1L, null, t)).isEmpty();
        assertThat(facade.closedSessions(1L, t, null)).isEmpty();
        assertThat(facade.closedSessions(1L, t, t)).isEmpty();
        assertThat(facade.closedSessions(1L, t.plusSeconds(1), t)).isEmpty();
        verify(sessions, never()).findEndedBetween(any(), any(), any(), any());
    }
}
