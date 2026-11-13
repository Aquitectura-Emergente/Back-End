package com.emsafe.devicemanagement.infrastructure.outbox;

import com.emsafe.devicemanagement.application.ports.DeviceConfigPublisher;
import com.emsafe.devicemanagement.application.ports.PublicationFailedException;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Publica el outbox de Device Management: los eventos se entregan dentro de la
 * aplicación y la configuración del dosímetro, además, al broker MQTT.
 *
 * <p>Cada fila entregada se guarda en el momento: la pasada programada no
 * corre dentro de una transacción (se llama desde la misma clase), y sin
 * guardar explícitamente {@code published_at} quedaba en null, las filas se
 * reenviaban en cada pasada y las nuevas nunca entraban en el lote.
 *
 * <p>Una configuración que no se pudo publicar detiene las siguientes de la
 * pasada: si una más vieja se publicara después, pisaría el retained vigente.
 * Cada fallo queda en el log con el id del evento y el motivo.
 */
@Component
@ConditionalOnProperty(name = "emsafe.device-management.outbox-relay-enabled",
        havingValue = "true", matchIfMissing = true)
public class DeviceManagementOutboxRelay {
    static final int BATCH = 50;
    private static final Logger log = LoggerFactory.getLogger(DeviceManagementOutboxRelay.class);
    private static final Map<String, Class<? extends DeviceDomainEvent>> TYPES = Stream.of(
                    DeviceConfigChanged.class, DeviceConnectivityChanged.class, DeviceInactivityDetected.class,
                    TelemetryReadingsSynchronized.class, PlugStateReported.class, PlugCommandRequested.class,
                    EdgeConnectivityChanged.class)
            .collect(Collectors.toMap(Class::getSimpleName, c -> c));

    private final DeviceManagementEventJpaRepository events;
    private final ApplicationEventPublisher publisher;
    private final DeviceConfigPublisher configPublisher;
    private final ObjectMapper json;
    private final Clock clock;

    public DeviceManagementOutboxRelay(DeviceManagementEventJpaRepository events, ApplicationEventPublisher publisher,
                                       ObjectProvider<DeviceConfigPublisher> configPublisher, ObjectMapper json,
                                       Clock clock) {
        this.events = events;
        this.publisher = publisher;
        DeviceConfigPublisher target = configPublisher == null ? null : configPublisher.getIfAvailable();
        this.configPublisher = target != null ? target : config -> {
            throw new PublicationFailedException("no DeviceConfigPublisher is configured", null);
        };
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
    public int drainOnce() {
        List<DeviceManagementEvent> pending = events.findByPublishedAtIsNullOrderByOccurredAtAsc(PageRequest.of(0, BATCH));
        int delivered = 0;
        int heldBack = 0;
        boolean configBlocked = false;
        for (DeviceManagementEvent row : pending) {
            Class<? extends DeviceDomainEvent> type = TYPES.get(row.getEventType());
            if (type == null) {
                log.warn("Outbox event {} has unknown type {}; discarded", row.getId(), row.getEventType());
                markPublished(row);
                continue;
            }
            DeviceDomainEvent event;
            try {
                event = json.readValue(row.getPayload(), type);
            } catch (Exception e) {
                log.warn("Outbox event {} ({}) is unreadable; discarded: {}", row.getId(), row.getEventType(),
                        e.getMessage());
                markPublished(row);
                continue;
            }
            if (event instanceof DeviceConfigChanged config) {
                if (configBlocked) {
                    heldBack++;
                    continue;
                }
                try {
                    configPublisher.publish(config);
                } catch (RuntimeException e) {
                    configBlocked = true;
                    log.warn("Outbox event {} ({} v{} for {}) not published to MQTT: {}", row.getId(),
                            row.getEventType(), config.configVersion(), config.deviceId(), reason(e));
                    continue;
                }
            }
            try {
                publisher.publishEvent(event);
            } catch (RuntimeException e) {
                log.warn("Outbox event {} ({}) not delivered: {}", row.getId(), row.getEventType(), reason(e));
                continue;
            }
            markPublished(row);
            delivered++;
        }
        if (heldBack > 0) log.warn("{} later config event(s) held back behind a failed one", heldBack);
        if (delivered > 0) log.debug("Device management relay delivered {} event(s)", delivered);
        return delivered;
    }

    private void markPublished(DeviceManagementEvent row) {
        row.markPublished(clock.instant());
        events.save(row);
    }

    private static String reason(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
