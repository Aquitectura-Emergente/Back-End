package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.model.PlugCommandStatus;
import com.emsafe.devicemanagement.domain.model.PlugPower;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.domain.repositories.PlugCommandRepository;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Fachada pública de Device Management para los demás contextos. Reemplaza el
 * acceso directo al repositorio: lo que cambia el estado pasa por el agregado.
 * Los {@link Device} que devuelve son para lectura.
 */
@Service
@RequiredArgsConstructor
public class DeviceCatalog {
    private final DeviceRepository devices;
    private final PlugCommandRepository commands;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Device> listForClient(Long clientId) {
        return devices.findByClient(clientId);
    }

    @Transactional(readOnly = true)
    public Optional<Device> findForClient(Long deviceId, Long clientId) {
        return devices.findByIdAndClient(deviceId, clientId);
    }

    @Transactional(readOnly = true)
    public Optional<Device> findWithClient(Long deviceId) {
        return devices.findByIdWithClient(deviceId);
    }

    @Transactional(readOnly = true)
    public Device findBySerialNumber(String serialNumber) {
        return serialNumber == null ? null : devices.findBySerial(serialNumber.trim()).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Device> findAll() {
        return devices.findAll();
    }

    @Transactional(readOnly = true)
    public List<Device> listInZone(Long plantZoneId) {
        return devices.findByZone(plantZoneId);
    }

    @Transactional(readOnly = true)
    public long countInZone(Long plantZoneId) {
        return devices.countByZone(plantZoneId);
    }

    /** Asigna el sensor a una zona de planta; queda auditado. */
    @Transactional
    public void assignToZone(Long deviceId, Long plantZoneId) {
        Device device = devices.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));
        device.assignToZone(plantZoneId, "plant-configuration", clock.instant());
        devices.save(device);
    }

    /** Orden pendiente del usuario o, si no hay, el estado real del enchufe. Null si no es un enchufe. */
    @Transactional(readOnly = true)
    public String desiredPlugOf(Device device) {
        if (device.getDeviceType() != DeviceType.SMART_PLUG) return null;
        return commands.findLatestForPlug(device.getSerialNumber())
                .filter(c -> c.getStatus() == PlugCommandStatus.PENDING)
                .map(c -> c.getAction().name())
                .orElseGet(() -> device.getPlugPower() == null || device.getPlugPower() == PlugPower.UNKNOWN
                        ? null : device.getPlugPower().name());
    }

    /** Estado real del relé de un enchufe; para otros dispositivos, {@code fallback}. */
    public String plugStateOf(Device device, String fallback) {
        return device.getDeviceType() == DeviceType.SMART_PLUG && device.getPlugPower() != null
                ? device.getPlugPower().name() : fallback;
    }

    /** Dispositivos ya construidos (datos semilla). */
    @Transactional
    public List<Device> registerExisting(List<Device> list) {
        return devices.saveAll(list);
    }
}
