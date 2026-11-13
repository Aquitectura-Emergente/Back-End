package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.application.ports.AccountDirectory;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceSerial;
import com.emsafe.devicemanagement.domain.model.DeviceStatus;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.interfaces.rest.dto.CreateDeviceRequest;
import com.emsafe.devicemanagement.interfaces.rest.dto.DeviceDto;
import com.emsafe.devicemanagement.interfaces.rest.dto.DiscoverableDeviceDto;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.radiationmonitoring.application.RadiationReadingService;
import com.emsafe.radiationmonitoring.application.RadiationReadingService.RadiationReadingView;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DeviceService {
    private static final String ACTOR = "admin";

    private final DeviceRepository deviceRepository;
    private final AccountDirectory accounts;
    private final RadiationReadingService readings;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<DeviceDto> getAll() {
        return deviceRepository.findAllWithClient().stream().map(DeviceDto::from).toList();
    }

    /**
     * Sensores sin cliente (status = "unregistered"), cada uno con su última
     * lectura para que el técnico lo identifique durante una instalación.
     */
    @Transactional(readOnly = true)
    public List<DiscoverableDeviceDto> getDiscoverable() {
        return deviceRepository.findByStatus(DeviceStatus.UNREGISTERED).stream().map(d -> {
            RadiationReadingView last = d.getSerialNumber() == null ? null
                    : readings.latest(d.getSerialNumber()).orElse(null);
            return new DiscoverableDeviceDto(
                    d.getId(),
                    d.getSerialNumber(),
                    d.getName(),
                    d.getType(),
                    last != null ? last.value() : null,
                    last != null ? last.level() : null,
                    last != null ? last.recordedAt() : null,
                    d.getSerialNumber() != null ? readings.countBySensor(d.getSerialNumber()) : 0
            );
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<DeviceDto> getByClient(Long clientId) {
        return deviceRepository.findByClient(clientId).stream().map(DeviceDto::from).toList();
    }

    @Transactional(readOnly = true)
    public DeviceDto getById(Long id) {
        return deviceRepository.findByIdWithClient(id)
                .map(DeviceDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }

    @Transactional
    public DeviceDto create(CreateDeviceRequest req) {
        AppUser client = resolveClient(req.clientId());
        Device device;
        if (StringUtils.hasText(req.serialNumber())) {
            DeviceSerial serial = DeviceSerial.of(req.serialNumber());
            if (deviceRepository.findBySerial(serial.value()).isPresent()) {
                throw new BadRequestException("A device with serial '" + serial + "' is already registered");
            }
            device = Device.register(serial, parseType(req.deviceType()), req.name(), client, ACTOR, clock.instant());
        } else {
            device = Device.builder().name(req.name()).type(req.type()).client(client).build();
        }
        device.updateDetails(null, req.type(), req.location(), req.status(), null, parseDate(req.installDate()));
        return DeviceDto.from(deviceRepository.save(device));
    }

    @Transactional
    public DeviceDto update(Long id, CreateDeviceRequest req) {
        Device device = deviceRepository.findByIdWithClient(id)
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
        device.updateDetails(req.name(), req.type(), req.location(), req.status(), req.serialNumber(),
                parseDate(req.installDate()));
        // clientId = 0 desvincula; otro valor asigna ese cliente
        if (req.clientId() != null) {
            device.linkClient(req.clientId() == 0 ? null : resolveClient(req.clientId()), ACTOR, clock.instant());
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
        return accounts.findById(clientId).orElse(null);
    }

    private static DeviceType parseType(String value) {
        if (!StringUtils.hasText(value)) return DeviceType.FIXED_SENSOR;
        try {
            return DeviceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("deviceType must be PERSONAL_DOSIMETER, FIXED_SENSOR or SMART_PLUG");
        }
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
