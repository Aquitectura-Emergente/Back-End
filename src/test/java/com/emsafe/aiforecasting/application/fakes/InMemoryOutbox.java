package com.emsafe.aiforecasting.application.fakes;

import com.emsafe.aiforecasting.domain.ports.ForecastingOutboxPort;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.aiforecasting.domain.ports.PageResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryOutbox implements ForecastingOutboxPort {

    private final Map<String, OutboxEvent> store = new LinkedHashMap<>();

    @Override
    public void append(OutboxEvent event) {
        if (store.containsKey(event.eventId())) {
            throw new IllegalStateException("duplicate outbox eventId " + event.eventId());
        }
        store.put(event.eventId(), event);
    }

    @Override
    public PageResult<OutboxEvent> findPending(int page, int size) {
        // sort estable: a igual occurredAt conserva el orden de inserción
        List<OutboxEvent> pending = store.values().stream()
                .filter(OutboxEvent::isPending)
                .sorted(Comparator.comparing(OutboxEvent::occurredAt))
                .toList();
        int from = Math.min(page * size, pending.size());
        int to = Math.min(from + size, pending.size());
        return new PageResult<>(new ArrayList<>(pending.subList(from, to)), page, size, pending.size());
    }

    @Override
    public Optional<OutboxEvent> findById(String eventId) {
        return Optional.ofNullable(store.get(eventId));
    }

    @Override
    public Optional<OutboxEvent> acknowledge(String eventId, Instant publishedAt) {
        OutboxEvent current = store.get(eventId);
        if (current == null) {
            return Optional.empty();
        }
        if (current.isPending()) {
            current = current.markPublished(publishedAt);
            store.put(eventId, current);
        }
        return Optional.of(current);
    }

    public List<OutboxEvent> all() {
        return List.copyOf(store.values());
    }

    public List<OutboxEvent> ofType(String type) {
        return store.values().stream().filter(e -> e.eventType().equals(type)).toList();
    }
}
