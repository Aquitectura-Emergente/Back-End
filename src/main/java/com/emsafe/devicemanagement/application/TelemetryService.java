package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.interfaces.rest.dto.ReadingDto;
import com.emsafe.radiationmonitoring.application.RadiationReadingService;
import com.emsafe.radiationmonitoring.application.RadiationReadingService.RadiationReadingView;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Consulta de lecturas para el portal y la app. La ingesta llega por MQTT
 * ({@link TelemetryIngestionService}); las lecturas del hogar las guarda
 * Radiation Monitoring.
 */
@Service
@RequiredArgsConstructor
public class TelemetryService {
    private static final int MAX_LIMIT = 500;

    private final RadiationReadingService readings;

    public List<ReadingDto> list(String serialNumber, int limit) {
        int size = clampLimit(limit);
        List<RadiationReadingView> found = serialNumber != null && !serialNumber.isBlank()
                ? readings.bySensor(serialNumber.trim(), size)
                : readings.recent(size);
        return found.stream().map(TelemetryService::toDto).toList();
    }

    public ReadingDto latest(String serialNumber) {
        if (serialNumber == null || serialNumber.isBlank()) {
            throw new BadRequestException("serialNumber is required");
        }
        return readings.latest(serialNumber.trim()).map(TelemetryService::toDto)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No readings found for serialNumber: " + serialNumber));
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
