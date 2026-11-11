package com.emsafe.aiforecasting.infrastructure.persistence;

import com.emsafe.aiforecasting.domain.ports.ForecastingOutboxPort;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.aiforecasting.domain.ports.PageResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * Outbox en {@code af_outbox}. El payload se guarda como JSON; al leerlo, los enteros vuelven
 * como Long y los decimales como BigDecimal, para que el contrato no cambie de tipo.
 */
@Component
public class JpaForecastingOutboxAdapter implements ForecastingOutboxPort {

    private static final TypeReference<LinkedHashMap<String, Object>> PAYLOAD = new TypeReference<>() {
    };

    private final OutboxJpaRepository jpa;
    private final ObjectMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public JpaForecastingOutboxAdapter(OutboxJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void append(OutboxEvent event) {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.setEventId(event.eventId());
        entity.setEventType(event.eventType());
        entity.setSchemaVersion(event.schemaVersion());
        entity.setOrganizationId(event.organizationId());
        entity.setAggregateId(event.aggregateId());
        entity.setOccurredAt(event.occurredAt());
        entity.setCorrelationId(event.correlationId());
        entity.setCausationId(event.causationId());
        entity.setPayload(write(event));
        entity.setPublishedAt(event.publishedAt());
        jpa.saveAndFlush(entity);
    }

    @Override
    public PageResult<OutboxEvent> findPending(int page, int size) {
        Page<OutboxEventEntity> result =
                jpa.findByPublishedAtIsNullOrderByOccurredAtAscIdAsc(PageRequest.of(page, size));
        return new PageResult<>(result.map(this::toDomain).getContent(), page, size, result.getTotalElements());
    }

    @Override
    public Optional<OutboxEvent> findById(String eventId) {
        return jpa.findByEventId(eventId).map(this::toDomain);
    }

    @Override
    public Optional<OutboxEvent> acknowledge(String eventId, Instant publishedAt) {
        return jpa.findByEventId(eventId).map(entity -> {
            if (entity.getPublishedAt() == null) {
                entity.setPublishedAt(publishedAt);
                jpa.save(entity);
            }
            return toDomain(entity);
        });
    }

    private String write(OutboxEvent event) {
        try {
            return json.writeValueAsString(event.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize payload of outbox event " + event.eventId(), e);
        }
    }

    private OutboxEvent toDomain(OutboxEventEntity e) {
        try {
            return new OutboxEvent(e.getEventId(), e.getEventType(), e.getSchemaVersion(), e.getOrganizationId(),
                    e.getAggregateId(), e.getOccurredAt(), e.getCorrelationId(), e.getCausationId(),
                    json.readValue(e.getPayload(), PAYLOAD), e.getPublishedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("corrupt payload in outbox event " + e.getEventId(), ex);
        }
    }
}
