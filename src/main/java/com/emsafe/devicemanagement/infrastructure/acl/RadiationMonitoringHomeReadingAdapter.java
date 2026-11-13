package com.emsafe.devicemanagement.infrastructure.acl;

import com.emsafe.devicemanagement.application.ports.HomeReadingGateway;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.radiationmonitoring.application.RadiationReadingService;
import com.emsafe.radiationmonitoring.application.RadiationReadingService.HomeReading;
import com.emsafe.radiationmonitoring.application.RadiationReadingService.RadiationReadingView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/** Capa anticorrupción hacia Radiation Monitoring, dueño de las lecturas del hogar. */
@Component
@RequiredArgsConstructor
public class RadiationMonitoringHomeReadingAdapter implements HomeReadingGateway {
    private final RadiationReadingService readings;

    @Override
    public RecordedHomeReading record(Device device, AppUser home, BigDecimal valueUT, boolean saturated,
                                      Instant capturedAt) {
        RadiationReadingView v = readings.recordHomeReading(new HomeReading(device, home, valueUT, saturated, capturedAt));
        return new RecordedHomeReading(v.id(), v.sensorId(), v.deviceId(), v.deviceName(), v.value(), v.level(),
                v.message(), v.location(), v.latitude(), v.longitude(), v.clientId(), v.clientName(), v.recordedAt());
    }
}
