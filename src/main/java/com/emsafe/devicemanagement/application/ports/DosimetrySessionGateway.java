package com.emsafe.devicemanagement.application.ports;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Personal Dosimetry, visto desde Device Management. */
public interface DosimetrySessionGateway {
    /** Jornada del dosímetro que cubre la hora de captura. */
    Optional<String> sessionCovering(Long deviceId, Instant capturedAt);

    DosimetryResult ingest(String sessionId, DosimetryReading reading);

    record DosimetryReading(Long deviceId, String bootId, long sequence, BigDecimal fieldMicrotesla,
                            Instant capturedFrom, Instant capturedTo, boolean saturated) {
    }

    record DosimetryResult(Status status, String readingRef, String detail) {
        public enum Status { ACCEPTED, DUPLICATE, REJECTED }
    }
}
