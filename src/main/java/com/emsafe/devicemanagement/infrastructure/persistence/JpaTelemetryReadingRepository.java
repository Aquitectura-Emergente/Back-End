package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.ReadingIdentity;
import com.emsafe.devicemanagement.domain.model.ReadingRouting;
import com.emsafe.devicemanagement.domain.model.TelemetryReading;
import com.emsafe.devicemanagement.domain.repositories.TelemetryReadingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaTelemetryReadingRepository implements TelemetryReadingRepository {
    private final TelemetryReadingJpaRepository readings;

    @Override
    public TelemetryReading save(TelemetryReading reading) {
        return readings.save(reading);
    }

    @Override
    public Optional<TelemetryReading> findByIdentity(ReadingIdentity identity) {
        return readings.findByDeviceSerialAndSequenceNumberAndCapturedAt(
                identity.device().value(), identity.sequence(), identity.capturedAt());
    }

    @Override
    public List<TelemetryReading> findByDevice(String serialNumber, ReadingRouting routing, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        return routing == null
                ? readings.findByDeviceSerialOrderByCapturedAtDescIdDesc(serialNumber, page)
                : readings.findByDeviceSerialAndRoutingOrderByCapturedAtDescIdDesc(serialNumber, routing, page);
    }
}
