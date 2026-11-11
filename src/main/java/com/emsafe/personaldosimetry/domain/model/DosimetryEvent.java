package com.emsafe.personaldosimetry.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "personal_dosimetry_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DosimetryEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;
    @Column(nullable = false)
    private Long organizationId;
    @Column(nullable = false, length = 64)
    private String eventType;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
    @Column(nullable = false)
    private Instant occurredAt;
    private Instant publishedAt;

    public DosimetryEvent(Long organizationId, String eventType, String payload, Instant occurredAt) {
        this.organizationId = organizationId;
        this.eventType = eventType;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public void acknowledge(Instant now) {
        if (publishedAt == null) publishedAt = now;
    }
}
