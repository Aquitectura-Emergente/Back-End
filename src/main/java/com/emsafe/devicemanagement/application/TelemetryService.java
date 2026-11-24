package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.interfaces.rest.dto.ReadingDto;
import com.emsafe.radiationmonitoring.application.RadiationReadingService;
import com.emsafe.radiationmonitoring.application.RadiationReadingService.RadiationReadingView;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Predicate;

/**
 * Consulta de lecturas para el portal y la app. La ingesta llega por MQTT
 * ({@link TelemetryIngestionService}); las lecturas del hogar las guarda
 * Radiation Monitoring.
 *
 * <p>El administrador ve todo. Los demás piden un dispositivo concreto: el
 * dueño o el SSO de su organización ven todas sus lecturas, y un cliente al que
 * se le prestó solo las que se le atribuyeron a él.
 */
@Service
@RequiredArgsConstructor
public class TelemetryService {
    private static final int MAX_LIMIT = 500;

    private final RadiationReadingService readings;
    private final DeviceRepository devices;

    public List<ReadingDto> list(DeviceActor actor, String serialNumber, int limit) {
        int size = clampLimit(limit);
        if (serialNumber == null || serialNumber.isBlank()) {
            if (!actor.administrator()) throw new BadRequestException("serialNumber is required");
            return readings.recent(size).stream().map(TelemetryService::toDto).toList();
        }
        String serial = serialNumber.trim();
        return readings.bySensor(serial, size).stream()
                .filter(visibleTo(actor, serial))
                .map(TelemetryService::toDto).toList();
    }

    public ReadingDto latest(DeviceActor actor, String serialNumber) {
        if (serialNumber == null || serialNumber.isBlank()) {
            throw new BadRequestException("serialNumber is required");
        }
        String serial = serialNumber.trim();
        RadiationReadingView latest = readings.latest(serial)
                .orElseThrow(() -> new ResourceNotFoundException("No readings found for serialNumber: " + serial));
        if (!visibleTo(actor, serial).test(latest)) {
            throw new AccessDeniedException("This device is outside the authorized scope");
        }
        return toDto(latest);
    }

    private Predicate<RadiationReadingView> visibleTo(DeviceActor actor, String serial) {
        if (actor.administrator()) return r -> true;
        Long owner = devices.findBySerial(serial).map(Device::organizationId).orElse(null);
        boolean ownerOrSso = owner != null && ((actor.client() && owner.equals(actor.userId()))
                || (actor.ssoResponsible() && owner.equals(actor.organizationId())));
        if (ownerOrSso) return r -> true;
        return r -> actor.client() && actor.userId() != null && actor.userId().equals(r.clientId());
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) return 50;
        return Math.min(limit, MAX_LIMIT);
    }

    static ReadingDto toDto(RadiationReadingView r) {
        return ReadingDto.builder()
                .id(r.id())
                .serialNumber(r.sensorId())
                .deviceDbId(r.deviceId())
                .deviceName(r.deviceName())
                .fieldUT(r.value())
                .level(r.level())
                .message(r.message())
                .plug(r.plug())
                .location(r.location())
                .latitude(r.latitude())
                .longitude(r.longitude())
                .clientId(r.clientId())
                .clientName(r.clientName())
                .recordedAt(r.recordedAt())
                .build();
    }
}
