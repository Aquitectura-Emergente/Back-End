package com.emsafe.aiforecasting.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "af_outbox")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor
public class OutboxEventEntity {

    /** Orden de inserción, para desempatar eventos con el mismo occurredAt. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "aggregate_id", length = 64)
    private String aggregateId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "causation_id", length = 100)
    private String causationId;

    /** JSON del cuerpo del evento. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "published_at")
    private Instant publishedAt;
}
