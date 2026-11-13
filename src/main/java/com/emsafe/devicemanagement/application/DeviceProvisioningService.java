package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceSerial;
import com.emsafe.devicemanagement.domain.model.DeviceStatus;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.interfaces.rest.dto.DeviceDto;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.shared.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.List;

/**
 * Operaciones sobre el parque de dispositivos que dispara el trabajo de campo.
 *
 * Es la frontera con el contexto de órdenes de trabajo: pide lo que necesita
 * en el lenguaje del negocio —reclamar, instalar, recoger— y es Device
 * Management quien decide cómo se traduce eso al agregado Device.
 */
@Service
@RequiredArgsConstructor
public class DeviceProvisioningService {
    private static final String ACTOR = "field-service";

    private final DeviceRepository deviceRepository;
    private final Clock clock;

    /**
     * El técnico reclama un sensor que el edge ya descubrió. Se localiza por id
     * o por serial; si todavía no ha reportado nunca, se crea como red de
     * seguridad para no bloquear la instalación.
     */
    @Transactional
    public void claimDiscovered(Long deviceId, String serialNumber, String name,
                                String type, AppUser client, String location) {
        Device device = null;
        if (deviceId != null) {
            device = deviceRepository.findById(deviceId).orElse(null);
        }
        if (device == null && StringUtils.hasText(serialNumber)) {
            device = deviceRepository.findBySerial(serialNumber.trim()).orElse(null);
        }
        if (device == null) {
            if (!StringUtils.hasText(serialNumber)) {
                throw new BadRequestException("deviceId or serialNumber is required to claim a device");
            }
            device = Device.unregistered(serialNumber.trim());
        }
        device.claimFor(client, location, name, type);
        deviceRepository.save(device);
    }

    /** Alta directa tecleando el serial; si el serial ya existe, se reclama ese dispositivo. */
    @Transactional
    public void installNew(String serialNumber, String name, String type,
                           AppUser client, String location) {
        if (!StringUtils.hasText(serialNumber)) {
            throw new BadRequestException("Serial number is required for every installed device");
        }
        Device existing = deviceRepository.findBySerial(serialNumber.trim()).orElse(null);
        if (existing != null) {
            existing.claimFor(client, location, name, type);
            deviceRepository.save(existing);
            return;
        }
        Device device = Device.register(DeviceSerial.of(serialNumber), DeviceType.FIXED_SENSOR,
                StringUtils.hasText(name) ? name : "Sensor", client, ACTOR, clock.instant());
        device.updateDetails(null, StringUtils.hasText(type) ? type : "Sensor", location, null, null, null);
        deviceRepository.save(device);
    }

    /**
     * Cambia el estado operativo de un dispositivo. El agregado decide qué
     * implica: una recogida ("unregistered") además lo devuelve al almacén.
     */
    @Transactional
    public void changeStatus(Long deviceId, String status) {
        if (deviceId == null || !StringUtils.hasText(status)) return;
        deviceRepository.findById(deviceId).ifPresent(device -> {
            device.changeStatus(status);
            deviceRepository.save(device);
        });
    }

    /** Al arrancar un mantenimiento, los equipos marcados para revisión pasan a estar en mantenimiento. */
    @Transactional
    public void startMaintenanceFor(Long clientId) {
        deviceRepository
                .findByClientAndStatus(clientId, DeviceStatus.REQUIRES_MAINTENANCE)
                .forEach(d -> {
                    d.changeStatus(DeviceStatus.IN_MAINTENANCE);
                    deviceRepository.save(d);
                });
    }

    /** Equipos de un cliente, para mostrarlos en el detalle de la orden. */
    @Transactional(readOnly = true)
    public List<DeviceDto> listForClient(Long clientId) {
        return deviceRepository.findByClient(clientId)
                .stream().map(DeviceDto::from).toList();
    }
}
