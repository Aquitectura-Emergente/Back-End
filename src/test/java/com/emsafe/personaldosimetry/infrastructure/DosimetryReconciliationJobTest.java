package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.personaldosimetry.application.PersonalDosimetryService;
import com.emsafe.personaldosimetry.domain.model.ExposureSession;
import com.emsafe.personaldosimetry.infrastructure.persistence.ExposureSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DosimetryReconciliationJobTest {

    private static final Instant NOW = Instant.parse("2026-11-25T18:00:00.123456999Z");
    private static final Instant TRUNCATED_NOW = Instant.parse("2026-11-25T18:00:00.123456Z");

    private final ExposureSessionRepository sessions = mock(ExposureSessionRepository.class);
    private final PersonalDosimetryService service = mock(PersonalDosimetryService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void processesBatchIsolatesPerSessionFailureAndCountsOnlyFinalizedSessions() {
        when(sessions.findExpiredReconciliationIds(
                ExposureSession.State.CLOSING, TRUNCATED_NOW, PageRequest.of(0, 3)))
                .thenReturn(List.of("S-1", "S-2", "S-3"));
        when(service.finalizeExpiredClosingSession("S-1")).thenReturn(true);
        when(service.finalizeExpiredClosingSession("S-2")).thenThrow(new IllegalStateException("lock timeout"));
        when(service.finalizeExpiredClosingSession("S-3")).thenReturn(false);

        var job = new DosimetryReconciliationJob(sessions, service, clock, 3);

        assertThat(job.reconcileOnce()).isEqualTo(1);
        verify(service).finalizeExpiredClosingSession("S-1");
        verify(service).finalizeExpiredClosingSession("S-2");
        verify(service).finalizeExpiredClosingSession("S-3");
    }

    @Test
    void rejectsOutOfRangeBatchSize() {
        assertThatThrownBy(() -> new DosimetryReconciliationJob(sessions, service, clock, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DosimetryReconciliationJob(sessions, service, clock, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canBeDisabledWithProperty() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(ExposureSessionRepository.class, () -> sessions)
                .withBean(PersonalDosimetryService.class, () -> service)
                .withBean(Clock.class, () -> clock)
                .withUserConfiguration(DosimetryReconciliationJob.class);

        runner.run(ctx -> assertThat(ctx).hasSingleBean(DosimetryReconciliationJob.class));
        runner.withPropertyValues("emsafe.personal-dosimetry.reconciliation-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DosimetryReconciliationJob.class));
    }
}
