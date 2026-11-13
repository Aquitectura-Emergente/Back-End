package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.ReadingRouting;
import com.emsafe.devicemanagement.domain.model.TelemetryReading;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TelemetryReadingJpaRepository extends JpaRepository<TelemetryReading, Long> {
    Optional<TelemetryReading> findByDeviceSerialAndSequenceNumberAndCapturedAt(String deviceSerial,
                                                                               Long sequenceNumber,
                                                                               Instant capturedAt);

    List<TelemetryReading> findByDeviceSerialOrderByCapturedAtDescIdDesc(String deviceSerial, Pageable pageable);

    List<TelemetryReading> findByDeviceSerialAndRoutingOrderByCapturedAtDescIdDesc(String deviceSerial,
                                                                                 ReadingRouting routing,
                                                                                 Pageable pageable);
}
