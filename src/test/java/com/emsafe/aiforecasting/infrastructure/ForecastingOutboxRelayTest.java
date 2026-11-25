package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.ForecastingEventFactory;
import com.emsafe.aiforecasting.application.fakes.InMemoryOutbox;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.occupationalhealth.application.events.inbound.RotationSuggested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué despacha el relay de AI Forecasting y qué significa published_at en cada caso. */
class ForecastingOutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-11-25T15:00:00Z");

    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final List<Object> published = new ArrayList<>();
    private final ForecastingOutboxRelay relay =
            new ForecastingOutboxRelay(outbox, published::add, Clock.fixed(NOW, ZoneOffset.UTC));

    private static OutboxEvent event(String id, String type, Map<String, Object> payload) {
        return new OutboxEvent(id, type, OutboxEvent.SCHEMA_VERSION, 8L, "assessment-1",
                NOW.minusSeconds(60), "corr-1", "cause-1", payload, null);
    }

    @Test
    void rotationSuggestedIsPublishedToOccupationalHealthAndMarked() {
        outbox.append(event("ev-rot", ForecastingEventFactory.ROTATION_SUGGESTED, Map.of(
                "organizationId", 8L, "workerId", 101L, "suggestionId", "assessment-1",
                "candidateWorkerIds", List.of(200L), "criticality", "HIGH", "rationale", "r")));

        assertThat(relay.drainOnce()).isEqualTo(1);

        assertThat(published).singleElement().isInstanceOf(RotationSuggested.class);
        assertThat(outbox.findById("ev-rot").orElseThrow().publishedAt()).isEqualTo(NOW);
    }

    @Test
    void predictiveRiskDetectedIsInternalMarkedButNeverPublished() {
        assertThat(ForecastingOutboxRelay.INTERNAL_EVENT_TYPES)
                .containsExactly(ForecastingEventFactory.PREDICTIVE_RISK_DETECTED);
        outbox.append(event("ev-risk", ForecastingEventFactory.PREDICTIVE_RISK_DETECTED,
                Map.of("workerId", 101L, "criticality", "HIGH", "isProjection", true)));

        assertThat(relay.drainOnce()).isEqualTo(1);

        assertThat(published).isEmpty();
        OutboxEvent kept = outbox.findById("ev-risk").orElseThrow();
        assertThat(kept.publishedAt()).isEqualTo(NOW);
        assertThat(kept.payload()).containsEntry("isProjection", true);
    }

    @Test
    void anUnknownTypeStaysPendingInsteadOfBeingSilentlyMarked() {
        outbox.append(event("ev-new", "SomethingNew", Map.of()));

        assertThat(relay.drainOnce()).isZero();

        assertThat(published).isEmpty();
        assertThat(outbox.findById("ev-new").orElseThrow().publishedAt()).isNull();
    }
}
