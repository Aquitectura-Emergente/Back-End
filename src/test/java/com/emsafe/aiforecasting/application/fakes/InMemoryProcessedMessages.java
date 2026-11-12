package com.emsafe.aiforecasting.application.fakes;

import com.emsafe.aiforecasting.domain.ports.ProcessedMessagePort;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class InMemoryProcessedMessages implements ProcessedMessagePort {

    private final Map<String, Instant> store = new LinkedHashMap<>();

    @Override
    public boolean isProcessed(String eventId) {
        return store.containsKey(eventId);
    }

    @Override
    public void markProcessed(String eventId, Instant processedAt) {
        if (store.putIfAbsent(eventId, processedAt) != null) {
            throw new IllegalStateException("eventId already processed " + eventId);
        }
    }

    public int size() {
        return store.size();
    }
}
