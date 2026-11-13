package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.aiforecasting.domain.ports.PageResult;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.emsafe.aiforecasting.application.ForecastingTestContext.*;
import static org.assertj.core.api.Assertions.*;

class OutboxHandlersTest {

    private final ForecastingTestContext ctx = new ForecastingTestContext();

    private void twoTriggers() {
        ctx.seedHighScenario();
        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));
        ctx.clock.advance(Duration.ofMinutes(10));
        ctx.trigger.handle(warningCmd("w-2", 101L, "7300"));
    }

    @Test
    void pendingEventsOldestFirst() {
        twoTriggers();

        PageResult<OutboxEvent> pending = ctx.outboxQueries.pending(0, 50);

        assertThat(pending.totalElements()).isEqualTo(4);
        assertThat(pending.content()).extracting(OutboxEvent::causationId)
                .containsExactly("w-1", "w-1", "w-2", "w-2");
        assertThat(pending.content()).extracting(OutboxEvent::eventType).containsExactly(
                ForecastingEventFactory.ROTATION_SUGGESTED, ForecastingEventFactory.PREDICTIVE_RISK_DETECTED,
                ForecastingEventFactory.ROTATION_SUGGESTED, ForecastingEventFactory.PREDICTIVE_RISK_DETECTED);
    }

    @Test
    void ackRemovesFromPendingAndIsIdempotent() {
        twoTriggers();
        OutboxEvent first = ctx.outboxQueries.pending(0, 50).content().get(0);

        ctx.clock.advance(Duration.ofMinutes(1));
        OutboxEvent acked = ctx.ack.handle(first.eventId());
        ctx.clock.advance(Duration.ofMinutes(1));
        OutboxEvent again = ctx.ack.handle(first.eventId());

        assertThat(acked.publishedAt()).isEqualTo(at(12, 11));
        assertThat(again.publishedAt()).isEqualTo(at(12, 11));
        assertThat(ctx.outboxQueries.pending(0, 50).content())
                .hasSize(3)
                .extracting(OutboxEvent::eventId).doesNotContain(first.eventId());
    }

    @Test
    void ackOfUnknownEventIsNotFound() {
        assertThatThrownBy(() -> ctx.ack.handle("missing")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void pendingValidatesPaging() {
        assertThatThrownBy(() -> ctx.outboxQueries.pending(-1, 10)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ctx.outboxQueries.pending(0, 201)).isInstanceOf(BadRequestException.class);
        assertThat(ctx.outboxQueries.pending(0, 10).content()).isEmpty();
    }
}
