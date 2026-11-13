package com.emsafe.devicemanagement.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/** Fila del outbox de Device Management. */
@Entity
@Table(name = "device_management_events")
@Getter
public class DeviceManagementEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    @Column(name = "organization_id")
    private Long organizationId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "published_at")
    private Instant publishedAt;

    protected DeviceManagementEvent() {
    }

    public DeviceManagementEvent(String eventType, Long organizationId, String payload, Instant occurredAt) {
        this.eventType = eventType;
        this.organizationId = organizationId;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
    }
}
