package com.emsafe.iottelemetry.application;

import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import com.emsafe.iottelemetry.interfaces.rest.dto.CreateDeviceRequest;
import com.emsafe.iottelemetry.interfaces.rest.dto.DeviceDto;
import com.emsafe.iottelemetry.interfaces.rest.dto.DiscoverableDeviceDto;
import com.emsafe.iottelemetry.domain.model.Device;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.shared.exception.ResourceNotFoundException;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final RadiationReadingRepository readingRepository;

    public List<DeviceDto> getAll() {
        return deviceRepository.findAllWithClient().stream().map(DeviceDto::from).toList();
    }

    /**
     * Sensors discovered by the edge but not yet assigned to a client
     * (status = "unregistered"), each enriched with its latest live reading so
     * the technician can identify it (wiggle test) during an installation.
     * (Freshness-window filter to be added later.)
     */
    @Transactional(readOnly = true)
    public List<DiscoverableDeviceDto> getDiscoverable() {
        return deviceRepository.findByStatusOrderByIdAsc("unregistered").stream().map(d -> {
            RadiationReading last = readingRepository
                    .findBySensorId(d.getSerialNumber(), PageRequest.of(0, 1))
                    .stream().findFirst().orElse(null);
            return new DiscoverableDeviceDto(
                    d.getId(),
                    d.getSerialNumber(),
                    d.getName(),
                    d.getType(),
                    last != null ? last.getValue() : null,
                    last != null ? last.getLevel() : null,
                    last != null ? last.getRecordedAt() : null,
                    d.getSerialNumber() != null ? readingRepository.countBySensorId(d.getSerialNumber()) : 0
            );
        }).toList();
    }

    public List<DeviceDto> getByClient(Long clientId) {
        return deviceRepository.findByClient_IdOrderByIdAsc(clientId).stream().map(DeviceDto::from).toList();
    }

    public DeviceDto getById(Long id) {
        return deviceRepository.findByIdWithClient(id)
                .map(DeviceDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }

    @Transactional
    public DeviceDto create(CreateDeviceRequest req) {
        AppUser client = resolveClient(req.clientId());
        Device device = Device.builder()
                .name(req.name())
                .type(req.type())
                .location(req.location())
                .status(StringUtils.hasText(req.status()) ? req.status() : "active")
                .serialNumber(req.serialNumber())
                .installDate(parseDate(req.installDate()))
                .client(client)
                .build();
        return DeviceDto.from(deviceRepository.save(device));
    }

    @Transactional
    public DeviceDto update(Long id, CreateDeviceRequest req) {
        Device device = deviceRepository.findByIdWithClient(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        if (StringUtils.hasText(req.name())) device.setName(req.name());
        if (StringUtils.hasText(req.type())) device.setType(req.type());
        if (req.location() != null) device.setLocation(req.location());
        if (StringUtils.hasText(req.status())) device.setStatus(req.status());
        if (req.serialNumber() != null) device.setSerialNumber(req.serialNumber());
        if (StringUtils.hasText(req.installDate())) device.setInstallDate(parseDate(req.installDate()));
        // Allow clearing client by passing clientId = 0, or set new client
        if (req.clientId() != null) {
            device.setClient(req.clientId() == 0 ? null : resolveClient(req.clientId()));
        }
        return DeviceDto.from(deviceRepository.save(device));
    }

    @Transactional
    public void delete(Long id) {
        if (!deviceRepository.existsById(id)) {
            throw new ResourceNotFoundException("Device", id);
        }
        deviceRepository.deleteById(id);
    }

    private AppUser resolveClient(Long clientId) {
        if (clientId == null || clientId == 0) return null;
        return userRepository.findById(clientId).orElse(null);
    }

    private LocalDate parseDate(String dateStr) {
        if (!StringUtils.hasText(dateStr)) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }
}
