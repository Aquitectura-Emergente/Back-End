package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.events.DeviceDomainEvent;
import com.emsafe.devicemanagement.domain.repositories.DomainEventOutbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class JpaDomainEventOutbox implements DomainEventOutbox {
    private final DeviceManagementEventJpaRepository events;
    private final ObjectMapper json;
    private final Clock clock;

    @Override
    public void append(DeviceDomainEvent event) {
        Instant occurredAt = event.occurredAt() == null ? clock.instant() : event.occurredAt();
        try {
            events.save(new DeviceManagementEvent(event.eventType(), event.organizationId(),
                    json.writeValueAsString(event), occurredAt));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize device management event", e);
        }
    }
}
