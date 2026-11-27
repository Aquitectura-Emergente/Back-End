package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.personaldosimetry.domain.model.DosimetryEvent;
import com.emsafe.personaldosimetry.infrastructure.persistence.DosimetryEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La entrega inmediata no se puede atascar por un evento que falla: cada evento
 * va en su propia transacción, como en la pasada programada.
 */
class DosimetryOutboxImmediateDeliveryTest {

    private final DosimetryEventRepository events = mock(DosimetryEventRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DosimetryOutboxRelay relay = new DosimetryOutboxRelay(events, publisher, new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-11-27T18:00:00Z"), ZoneOffset.UTC));

    @Test
    void aStuckEventDoesNotHoldBackTheNextOne() {
        DosimetryEvent stuck = event("E-stuck", DosimetryOutboxRelay.SESSION_CLOSED,
                "{\"sessionId\":\"S-old\",\"workerId\":15,\"finalizedAt\":\"2026-11-14T01:30:56\"}");
        DosimetryEvent opened = event("E-open", DosimetryOutboxRelay.SESSION_OPENED,
                "{\"sessionId\":\"S-new\",\"workerId\":18,\"positionId\":3,\"deviceId\":25,"
                        + "\"limitVersionId\":7,\"accumulatedIndex\":0,\"limitIndex\":100,"
                        + "\"startedAt\":\"2026-11-27T18:00:00Z\"}");
        when(events.findByPublishedAtIsNullAndEventTypeInOrderByOccurredAtAsc(anyList(), any()))
                .thenReturn(new PageImpl<>(List.of(stuck, opened)));
        when(events.findById("E-stuck")).thenReturn(Optional.of(stuck));
        when(events.findById("E-open")).thenReturn(Optional.of(opened));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        // La segunda transacción (la del evento atascado) se deshace al confirmar.
        AtomicInteger commits = new AtomicInteger();
        doAnswer(inv -> {
            if (commits.incrementAndGet() == 2) throw new UnexpectedRollbackException("marked rollback-only");
            return null;
        }).when(transactions).commit(any());

        int delivered = relay.drainEachIsolated(new TransactionTemplate(transactions));

        assertThat(delivered).isEqualTo(1);
        assertThat(opened.getPublishedAt()).isNotNull();
        verify(events).save(opened);
    }

    @Test
    void anEventAlreadyPublishedByAnotherPassIsSkipped() {
        DosimetryEvent opened = event("E-open", DosimetryOutboxRelay.SESSION_OPENED, "{}");
        opened.acknowledge(Instant.parse("2026-11-27T17:59:00Z"));
        when(events.findByPublishedAtIsNullAndEventTypeInOrderByOccurredAtAsc(anyList(), any()))
                .thenReturn(new PageImpl<>(List.of(opened)));
        when(events.findById("E-open")).thenReturn(Optional.of(opened));
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        assertThat(relay.drainEachIsolated(new TransactionTemplate(transactions))).isZero();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    private static DosimetryEvent event(String id, String type, String payload) {
        DosimetryEvent event = new DosimetryEvent(8L, type, payload, Instant.parse("2026-11-27T18:00:00Z"));
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }
}
