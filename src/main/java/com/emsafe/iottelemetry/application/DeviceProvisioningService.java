package com.emsafe.iottelemetry.application;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iottelemetry.domain.model.Device;
import com.emsafe.iottelemetry.domain.model.DeviceStatus;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.iottelemetry.interfaces.rest.dto.DeviceDto;
import com.emsafe.shared.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/**
 * Operaciones sobre el parque de dispositivos que dispara el trabajo de campo.
 *
 * Existe como frontera entre bounded contexts: antes, WorkOrderService inyectaba
 * DeviceRepository y manipulaba el estado de los sensores a mano, con literales
 * sueltos. El contexto de órdenes de trabajo ahora pide lo que necesita en el
 * lenguaje del negocio —reclamar, instalar, recoger— y es iottelemetry quien
 * decide cómo se traduce eso al agregado Device.
 */
@Service
@RequiredArgsConstructor
public class DeviceProvisioningService {

    private final DeviceRepository deviceRepository;

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
            device = deviceRepository.findBySerialNumber(serialNumber.trim());
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

    /** Alta directa tecleando el serial, sin pasar por el descubrimiento del edge. */
    @Transactional
    public void installNew(String serialNumber, String name, String type,
                           AppUser client, String location) {
        if (!StringUtils.hasText(serialNumber)) {
            throw new BadRequestException("Serial number is required for every installed device");
        }
        Device device = Device.builder()
                .name(StringUtils.hasText(name) ? name : "Sensor")
                .type(StringUtils.hasText(type) ? type : "Sensor")
                .location(location)
                .status(DeviceStatus.ACTIVE)
                .serialNumber(serialNumber)
                .installDate(LocalDate.now())
                .client(client)
                .build();
        deviceRepository.save(device);
    }

    /**
     * Cambia el estado operativo de un dispositivo. El agregado decide qué
     * implica: una recogida ("unregistered") además lo devuelve al pool.
     */
    @Transactional
    public void changeStatus(Long deviceId, String status) {
        if (deviceId == null || !StringUtils.hasText(status)) return;
        deviceRepository.findById(deviceId).ifPresent(device -> {
            device.changeStatus(status);
            deviceRepository.save(device);
        });
    }

    /**
     * Al arrancar un mantenimiento, los equipos del cliente marcados para
     * revisión pasan a estar en mantenimiento.
     */
    @Transactional
    public void startMaintenanceFor(Long clientId) {
        deviceRepository
                .findByClient_IdAndStatusOrderByIdAsc(clientId, DeviceStatus.REQUIRES_MAINTENANCE)
                .forEach(d -> {
                    d.changeStatus(DeviceStatus.IN_MAINTENANCE);
                    deviceRepository.save(d);
                });
    }

    /** Equipos de un cliente, para mostrarlos en el detalle de la orden. */
    @Transactional(readOnly = true)
    public List<DeviceDto> listForClient(Long clientId) {
        return deviceRepository.findByClient_IdOrderByIdAsc(clientId)
                .stream().map(DeviceDto::from).toList();
    }
}
