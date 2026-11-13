package com.emsafe.devicemanagement.infrastructure.outbox;

import com.emsafe.devicemanagement.application.ports.DeviceConfigPublisher;
import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.domain.events.DeviceInactivityDetected;
import com.emsafe.devicemanagement.domain.model.ConfigDefinedBy;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceManagementEvent;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceManagementEventJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceManagementOutboxRelayTest {
    static final Instant T0 = Instant.parse("2026-11-12T14:00:00Z");
    final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    @Test
    void aFailedConfigHoldsBackLaterConfigsButNotOtherEvents() throws Exception {
        List<DeviceManagementEvent> rows = List.of(
                row(config(2)), row(new DeviceInactivityDetected("D", 7L, T0, T0)), row(config(3)));
        DeviceManagementEventJpaRepository repo = mock(DeviceManagementEventJpaRepository.class);
        when(repo.findByPublishedAtIsNullOrderByOccurredAtAsc(any())).thenAnswer(i ->
                rows.stream().filter(r -> r.getPublishedAt() == null).toList());
        List<Integer> published = new ArrayList<>();
        boolean[] brokerUp = {false};
        DeviceConfigPublisher mqtt = c -> {
            if (!brokerUp[0]) return false;
            published.add(c.configVersion());
            return true;
        };
        List<Object> inProcess = new ArrayList<>();
        ApplicationEventPublisher app = inProcess::add;
        var relay = new DeviceManagementOutboxRelay(repo, app, provider(mqtt), json, Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(rows.get(0).getPublishedAt()).isNull();
        assertThat(rows.get(1).getPublishedAt()).isNotNull();
        assertThat(rows.get(2).getPublishedAt()).isNull();

        brokerUp[0] = true;
        relay.drainOnce();
        assertThat(published).containsExactly(2, 3);
        assertThat(inProcess).hasSize(3);
    }

    private DeviceConfigChanged config(int version) {
        return new DeviceConfigChanged("EMSafe-DOS-01", 7L, version, new BigDecimal("300.0"),
                ConfigDefinedBy.SSO_RESPONSIBLE, 1000, 300, T0.plusSeconds(version));
    }

    private DeviceManagementEvent row(com.emsafe.devicemanagement.domain.events.DeviceDomainEvent e) throws Exception {
        return new DeviceManagementEvent(e.eventType(), e.organizationId(), json.writeValueAsString(e), e.occurredAt());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DeviceConfigPublisher> provider(DeviceConfigPublisher p) {
        ObjectProvider<DeviceConfigPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(p);
        return provider;
    }
}
