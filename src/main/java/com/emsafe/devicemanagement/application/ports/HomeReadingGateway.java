package com.emsafe.devicemanagement.application.ports;

import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.iam.domain.model.AppUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/** Flujo del hogar: registra la lectura y la clasifica. */
public interface HomeReadingGateway {
    RecordedHomeReading record(Device device, AppUser home, BigDecimal valueUT, boolean saturated, Instant capturedAt);

    record RecordedHomeReading(Long readingId, String serialNumber, Long deviceId, String deviceName, Double fieldUT,
                               String level, String message, String location, Double latitude, Double longitude,
                               Long clientId, String clientName, LocalDateTime recordedAt) {
    }
}
