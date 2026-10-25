package com.emsafe.iottelemetry.application;

import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import com.emsafe.iottelemetry.domain.model.Device;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import com.emsafe.iottelemetry.interfaces.rest.dto.ReadingDto;
import com.emsafe.iottelemetry.interfaces.rest.dto.ReadingIngestRequest;
import com.emsafe.iam.domain.model.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Ingesta de telemetría desde el edge IoT y consulta de lecturas para web/móvil.
 *
 * Enlace lectura → device → cliente: el edge manda `deviceId` (= serial del
 * sensor). Si el serial ya está registrado (con su cliente), la lectura queda
 * ligada automáticamente. Si no existe, se autocrea un device "unregistered"
 * (sin cliente) para no perder datos; luego se le asignará cliente desde el
 * portal (flujo de "reclamar sensor", pendiente).
 */
@Service
@RequiredArgsConstructor
public class TelemetryService {

    private static final int MAX_LIMIT = 500;

    private final RadiationReadingRepository readingRepository;
    private final DeviceRepository deviceRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public ReadingDto ingest(ReadingIngestRequest req) {
        if (req == null || req.getSerialNumber() == null || req.getSerialNumber().isBlank()) {
            throw new BadRequestException("serialNumber is required");
        }
        if (req.getFieldUT() == null) {
            throw new BadRequestException("field_uT is required");
        }

        String serial = req.getSerialNumber().trim();
        Device device = deviceRepository.findBySerialNumber(serial);
        if (device == null) {
            // Sensor aún no registrado: lo autocreamos para no perder la lectura.
            device = deviceRepository.save(Device.unregistered(serial));
        }

        LocalDateTime now = LocalDateTime.now();
        AppUser client = device.getClient();

        RadiationReading reading = RadiationReading.builder()
                .readingDate(LocalDate.now())
                .recordedAt(now)
                .value(req.getFieldUT())
                .level(req.getLevel())
                .message(req.getMessage())
                .plug(req.getPlug())
                .sensorId(serial)
                .location(device.getLocation())
                .latitude(client != null ? client.getLatitude() : null)
                .longitude(client != null ? client.getLongitude() : null)
                .device(device)
                .build();

        ReadingDto dto = toDto(readingRepository.save(reading));

        // Real-time push (STOMP / SimpleBroker). Same payload for web + mobile.
        // Global topic: consumed by admin/technician web (radiation map, dashboard),
        // which need to see every sensor.
        messagingTemplate.convertAndSend("/topic/readings", dto);
        // Per-client topic: a client (mobile app) only subscribes to its own readings,
        // so it never receives — or gets DANGER alerts about — other clients' sensors.
        if (client != null) {
            messagingTemplate.convertAndSend("/topic/clients/" + client.getId() + "/readings", dto);
        }
        // Unassigned sensors also feed the technician's installation discovery panel.
        if (device.isUnregistered()) {
            messagingTemplate.convertAndSend("/topic/discovery", dto);
        }

        return dto;
    }

    @Transactional(readOnly = true)
    public List<ReadingDto> list(String serialNumber, int limit) {
        Pageable pageable = PageRequest.of(0, clampLimit(limit));
        List<RadiationReading> readings = (serialNumber != null && !serialNumber.isBlank())
                ? readingRepository.findBySensorId(serialNumber.trim(), pageable)
                : readingRepository.findRecent(pageable);
        return readings.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ReadingDto latest(String serialNumber) {
        if (serialNumber == null || serialNumber.isBlank()) {
            throw new BadRequestException("serialNumber is required");
        }
        return readingRepository.findBySensorId(serialNumber.trim(), PageRequest.of(0, 1))
                .stream().findFirst()
                .map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No readings found for serialNumber: " + serialNumber));
    }

    /**
     * Estado DESEADO del relé para un sensor — lo consulta el EDGE en cada ciclo
     * para saber si el usuario ordenó abrir/cerrar la corriente (camino de vuelta
     * mobile → backend → edge → dispositivo). Devuelve null si no hay orden.
     */
    @Transactional(readOnly = true)
    public String getDesiredPlug(String serialNumber) {
        if (serialNumber == null || serialNumber.isBlank()) {
            throw new BadRequestException("serialNumber is required");
        }
        Device device = deviceRepository.findBySerialNumber(serialNumber.trim());
        if (device == null) {
            throw new ResourceNotFoundException("Device", 0L);
        }
        return device.getDesiredPlug();
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return 50;
        return Math.min(limit, MAX_LIMIT);
    }

    private ReadingDto toDto(RadiationReading r) {
        Device d = r.getDevice();
        AppUser client = d != null ? d.getClient() : null;
        return ReadingDto.builder()
                .id(r.getId())
                .serialNumber(r.getSensorId())
                .deviceDbId(d != null ? d.getId() : null)
                .deviceName(d != null ? d.getName() : null)
                .fieldUT(r.getValue())
                .level(r.getLevel())
                .message(r.getMessage())
                .plug(r.getPlug())
                .location(r.getLocation())
                .latitude(r.getLatitude())
                .longitude(r.getLongitude())
                .clientId(client != null ? client.getId() : null)
                .clientName(client != null ? client.getName() : null)
                .recordedAt(r.getRecordedAt())
                .build();
    }
}
