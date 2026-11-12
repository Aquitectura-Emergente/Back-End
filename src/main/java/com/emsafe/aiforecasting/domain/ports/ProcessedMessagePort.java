package com.emsafe.aiforecasting.domain.ports;

import java.time.Instant;

/** Idempotencia del inbox ({@code af_processed_message}). */
public interface ProcessedMessagePort {

    boolean isProcessed(String eventId);

    void markProcessed(String eventId, Instant processedAt);
}
