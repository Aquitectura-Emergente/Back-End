package com.emsafe.devicemanagement.infrastructure.outbox;

import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;
import com.emsafe.devicemanagement.domain.events.DeviceConnectivityChanged;
import com.emsafe.devicemanagement.domain.events.DeviceDomainEvent;
import com.emsafe.devicemanagement.domain.events.DeviceInactivityDetected;
import com.emsafe.devicemanagement.domain.events.EdgeConnectivityChanged;
import com.emsafe.devicemanagement.domain.events.PlugCommandRequested;
import com.emsafe.devicemanagement.domain.events.PlugStateReported;
import com.emsafe.devicemanagement.domain.events.TelemetryReadingsSynchronized;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceManagementEvent;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceManagementEventJpaRepository;
import com.emsafe.devicemanagement.application.ports.DeviceConfigPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Publica el outbox de Device Management: los eventos se entregan dentro de la
 * aplicación y la configuración del dosímetro, además, al broker MQTT.
 *
 * <p>Una configuración que no se pudo publicar detiene las siguientes de la
 * pasada: si una más vieja se publicara después, pisaría el retained vigente.
 */
@Component
@ConditionalOnProperty(name = "emsafe.device-management.outbox-relay-enabled",
        havingValue = "true", matchIfMissing = true)
public class DeviceManagementOutboxRelay {
    private static final int BATCH = 50;
    private static final Logger log = LoggerFactory.getLogger(DeviceManagementOutboxRelay.class);
    private static final Map<String, Class<? extends DeviceDomainEvent>> TYPES = Stream.of(
                    DeviceConfigChanged.class, DeviceConnectivityChanged.class, DeviceInactivityDetected.class,
                    TelemetryReadingsSynchronized.class, PlugStateReported.class, PlugCommandRequested.class,
                    EdgeConnectivityChanged.class)
            .collect(Collectors.toMap(Class::getSimpleName, c -> c));

    private final DeviceManagementEventJpaRepository events;
    private final ApplicationEventPublisher publisher;
    private final Predicate<DeviceConfigChanged> configPublisher;
    private final ObjectMapper json;
    private final Clock clock;

    public DeviceManagementOutboxRelay(DeviceManagementEventJpaRepository events, ApplicationEventPublisher publisher,
                                       ObjectProvider<DeviceConfigPublisher> configPublisher, ObjectMapper json,
                                       Clock clock) {
        this.events = events;
        this.publisher = publisher;
        DeviceConfigPublisher target = configPublisher == null ? null : configPublisher.getIfAvailable();
        this.configPublisher = target == null ? e -> false : target::publish;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${emsafe.device-management.outbox-relay-delay-ms:5000}")
    public void drainScheduled() {
        try {
            drainOnce();
        } catch (RuntimeException e) {
            log.warn("Device management outbox relay pass failed: {}", e.toString());
        }
    }

    /** Una pasada; devuelve cuántos eventos se entregaron. */
    @Transactional
    public int drainOnce() {
        List<DeviceManagementEvent> pending = events.findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0, BATCH));
        int delivered = 0;
        boolean configBlocked = false;
        for (DeviceManagementEvent row : pending) {
            Class<? extends DeviceDomainEvent> type = TYPES.get(row.getEventType());
            if (type == null) {
                row.markPublished(clock.instant());
                continue;
            }
            DeviceDomainEvent event;
            try {
                event = json.readValue(row.getPayload(), type);
            } catch (Exception e) {
                log.error("Device management event {} is unreadable, skipping: {}", row.getId(), e.toString());
                row.markPublished(clock.instant());
                continue;
            }
            if (event instanceof DeviceConfigChanged config) {
                if (configBlocked || !configPublisher.test(config)) {
                    configBlocked = true;
                    continue;
                }
            }
            publisher.publishEvent(event);
            row.markPublished(clock.instant());
            delivered++;
        }
        if (delivered > 0) log.debug("Device management relay delivered {} event(s)", delivered);
        return delivered;
    }
}
