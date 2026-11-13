package com.emsafe.devicemanagement.infrastructure.outbox;

import com.emsafe.devicemanagement.application.ports.DeviceConfigPublisher;
import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.domain.events.DeviceInactivityDetected;
import com.emsafe.devicemanagement.domain.model.ConfigDefinedBy;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceManagementEventJpaRepository;
import com.emsafe.devicemanagement.infrastructure.persistence.JpaDomainEventOutbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El caso real: la pasada programada no corre en una transacción. Antes de la
 * corrección {@code published_at} no se guardaba, las mismas filas se
 * reenviaban en cada pasada y un cambio de umbral posterior a las primeras 50
 * filas pendientes no se publicaba nunca.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:dmrelay;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE"
})
@ActiveProfiles("test")
@Import({JpaDomainEventOutbox.class, DeviceManagementOutboxRelayPersistenceTest.Support.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeviceManagementOutboxRelayPersistenceTest {
    static final Instant T0 = Instant.parse("2026-11-12T14:00:00Z");

    @TestConfiguration
    static class Support {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().addModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(T0, ZoneOffset.UTC);
        }
    }

    @Autowired JpaDomainEventOutbox outbox;
    @Autowired DeviceManagementEventJpaRepository events;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;

    @Test
    void aThresholdChangedAfterManyEventsIsPublishedAndEveryDeliveredRowIsMarked() {
        events.deleteAll();
        outbox.append(config(1, T0));
        for (int i = 0; i < DeviceManagementOutboxRelay.BATCH + 10; i++) {
            outbox.append(new DeviceInactivityDetected("S-" + i, 7L, T0, T0.plusSeconds(10 + i)));
        }
        outbox.append(config(2, T0.plusSeconds(3600)));

        List<Integer> published = new ArrayList<>();
        DeviceConfigPublisher mqtt = c -> published.add(c.configVersion());
        var relay = new DeviceManagementOutboxRelay(events, e -> { }, provider(mqtt), json, clock);

        relay.drainScheduled();
        relay.drainScheduled();
        relay.drainScheduled();

        assertThat(published).containsExactly(1, 2);
        assertThat(events.findByPublishedAtIsNullOrderByOccurredAtAsc(
                org.springframework.data.domain.PageRequest.of(0, 100))).isEmpty();
        assertThat(events.findByEventTypeOrderByOccurredAtAsc("DeviceConfigChanged"))
                .allSatisfy(row -> assertThat(row.getPublishedAt()).isEqualTo(T0));
    }

    private static DeviceConfigChanged config(int version, Instant at) {
        return new DeviceConfigChanged("EMSafe-DOS-01", 7L, version, new BigDecimal("250.0"),
                ConfigDefinedBy.SSO_RESPONSIBLE, 1000, 300, at);
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<DeviceConfigPublisher> provider(
            DeviceConfigPublisher p) {
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(p);
        return provider;
    }
}
