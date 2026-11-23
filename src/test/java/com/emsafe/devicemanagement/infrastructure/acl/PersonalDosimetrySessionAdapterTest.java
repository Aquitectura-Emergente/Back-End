package com.emsafe.devicemanagement.infrastructure.acl;

import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway.DosimetryReading;
import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway.DosimetryResult;
import com.emsafe.personaldosimetry.application.PersonalDosimetryService;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.IngestReading;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryResources;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalDosimetrySessionAdapterTest {
    private static final Instant STARTED = Instant.parse("2026-11-23T13:00:00Z");

    private final PersonalDosimetryService dosimetry = mock(PersonalDosimetryService.class);
    private final PersonalDosimetrySessionAdapter adapter =
            new PersonalDosimetrySessionAdapter(dosimetry, mock(PlatformTransactionManager.class));

    @BeforeEach
    void sessionStartedAtNine() {
        DosimetryResources.Session session = mock(DosimetryResources.Session.class);
        when(session.startedAt()).thenReturn(STARTED);
        when(dosimetry.getSession(any(), eq("s-1"))).thenReturn(session);
    }

    @Test
    void theFirstWindowOfASessionStartsWhenTheSessionOpens() {
        IngestReading sent = ingest(STARTED.minusSeconds(20), STARTED.plusSeconds(10));
        assertThat(sent.capturedFrom()).isEqualTo(STARTED);
        assertThat(sent.capturedTo()).isEqualTo(STARTED.plusSeconds(10));
    }

    @Test
    void laterWindowsKeepTheirFullInterval() {
        IngestReading sent = ingest(STARTED.plusSeconds(30), STARTED.plusSeconds(60));
        assertThat(sent.capturedFrom()).isEqualTo(STARTED.plusSeconds(30));
    }

    private IngestReading ingest(Instant from, Instant to) {
        DosimetryResult result = adapter.ingest("s-1",
                new DosimetryReading(7L, "boot-1", 1, new BigDecimal("50"), from, to, false));
        assertThat(result.status()).isNotNull();
        ArgumentCaptor<IngestReading> captor = ArgumentCaptor.forClass(IngestReading.class);
        verify(dosimetry).ingest(any(), eq("s-1"), captor.capture());
        return captor.getValue();
    }
}
