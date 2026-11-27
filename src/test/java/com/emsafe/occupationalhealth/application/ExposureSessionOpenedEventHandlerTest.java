package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.ShiftUpdated;
import com.emsafe.occupationalhealth.application.events.inbound.ExposureSessionOpened;
import com.emsafe.occupationalhealth.domain.model.WorkerShift;
import com.emsafe.occupationalhealth.domain.services.JobPositionZonePort;
import com.emsafe.occupationalhealth.infrastructure.InboxDeduplicator;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExposureSessionOpenedEventHandlerTest {

    private final WorkerShiftRepository shifts = mock(WorkerShiftRepository.class);
    private final StartWorkerShiftCommandHandler starter = mock(StartWorkerShiftCommandHandler.class);
    private final JobPositionZonePort zones = mock(JobPositionZonePort.class);
    private final InboxDeduplicator inbox = mock(InboxDeduplicator.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ExposureSessionOpenedEventHandler handler =
            new ExposureSessionOpenedEventHandler(shifts, starter, zones, inbox, events, transactions);

    private final ExposureSessionOpened opened =
            new ExposureSessionOpened(EventEnvelope.of(8L), "S-1", 12L, 31L);

    @BeforeEach
    void freshEvent() {
        when(inbox.markAsProcessed(anyString(), any())).thenReturn(true);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void aFailureNeverReachesTheRelay() {
        when(shifts.findActiveByWorker(8L, 12L)).thenThrow(new IllegalStateException("db down"));

        handler.on(opened);

        verify(starter, never()).handle(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void opensTheShiftInTheZoneOfThePositionAndTellsAiForecasting() {
        when(shifts.findActiveByWorker(8L, 12L)).thenReturn(Optional.empty());
        when(zones.zoneOf(8L, 31L)).thenReturn(Optional.of(4L));

        handler.on(opened);

        verify(starter).handle(8L, 12L, 31L, 4L, "S-1");
        ArgumentCaptor<ShiftUpdated> update = ArgumentCaptor.forClass(ShiftUpdated.class);
        verify(events).publishEvent(update.capture());
        assertThat(update.getValue().sessionId()).isEqualTo("S-1");
        assertThat(update.getValue().newZoneId()).isEqualTo(4L);
        assertThat(update.getValue().newPositionId()).isEqualTo(31L);
    }

    @Test
    void anOpenShiftWithoutSessionIsLinkedInsteadOfOpeningAnother() {
        WorkerShift manual = WorkerShift.start(8L, 12L, 31L, 4L, LocalDateTime.now());
        when(shifts.findActiveByWorker(8L, 12L)).thenReturn(Optional.of(manual));

        handler.on(opened);

        assertThat(manual.getExposureSessionId()).isEqualTo("S-1");
        verify(shifts).save(manual);
        verify(starter, never()).handle(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void aPositionWithoutZoneLeavesTheSessionWithoutShift() {
        when(shifts.findActiveByWorker(8L, 12L)).thenReturn(Optional.empty());
        when(zones.zoneOf(8L, 31L)).thenReturn(Optional.empty());

        handler.on(opened);

        verify(starter, never()).handle(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aRepeatedEventIsIgnored() {
        when(inbox.markAsProcessed(anyString(), any())).thenReturn(false);

        handler.on(opened);

        verify(shifts, never()).findActiveByWorker(anyLong(), anyLong());
    }
}
