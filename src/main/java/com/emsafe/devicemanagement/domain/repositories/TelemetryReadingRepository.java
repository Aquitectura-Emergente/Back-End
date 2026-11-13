package com.emsafe.devicemanagement.domain.repositories;

import com.emsafe.devicemanagement.domain.model.ReadingIdentity;
import com.emsafe.devicemanagement.domain.model.ReadingRouting;
import com.emsafe.devicemanagement.domain.model.TelemetryReading;

import java.util.List;
import java.util.Optional;

public interface TelemetryReadingRepository {
    TelemetryReading save(TelemetryReading reading);

    Optional<TelemetryReading> findByIdentity(ReadingIdentity identity);

    /** Las más recientes primero; {@code routing} null trae todas. */
    List<TelemetryReading> findByDevice(String serialNumber, ReadingRouting routing, int limit);
}
