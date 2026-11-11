package com.emsafe.aiforecasting.domain.ports;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;
import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/**
 * Evento saliente con el sobre común. {@code causationId} es el eventId del evento entrante;
 * {@code publishedAt} es {@code null} mientras esté pendiente.
 */
public record OutboxEvent(String eventId,
                          String eventType,
                          int schemaVersion,
                          Long organizationId,
                          String aggregateId,
                          Instant occurredAt,
                          String correlationId,
                          String causationId,
                          Map<String, Object> payload,
                          Instant publishedAt) {

    public static final int SCHEMA_VERSION = 1;

    public OutboxEvent {
        requireText(eventId, "eventId");
        requireText(eventType, "eventType");
        requirePresent(organizationId, "organizationId");
        requirePresent(occurredAt, "occurredAt");
        requirePresent(payload, "payload");
        // Conserva el orden y admite valores nulos (p. ej. projectedExceedanceAt sin excedencia).
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public OutboxEvent markPublished(Instant at) {
        return new OutboxEvent(eventId, eventType, schemaVersion, organizationId, aggregateId, occurredAt,
                correlationId, causationId, payload, at);
    }

    public boolean isPending() {
        return publishedAt == null;
    }
}
