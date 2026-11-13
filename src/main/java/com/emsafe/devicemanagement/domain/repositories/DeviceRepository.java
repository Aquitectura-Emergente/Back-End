package com.emsafe.devicemanagement.domain.repositories;

import com.emsafe.devicemanagement.domain.model.Device;

import java.util.List;
import java.util.Optional;

/**
 * Puerto del agregado {@link Device}. Guardar un dispositivo guarda también
 * su auditoría, su historial de configuración y sus eventos pendientes.
 */
public interface DeviceRepository {
    Device save(Device device);

    List<Device> saveAll(List<Device> devices);

    Optional<Device> findById(Long id);

    Optional<Device> findByIdWithClient(Long id);

    Optional<Device> findBySerial(String serialNumber);

    /** Bloquea el dispositivo hasta el fin de la transacción: serializa la ingesta por dispositivo. */
    Optional<Device> lockBySerial(String serialNumber);

    List<Device> findAll();

    List<Device> findAllWithClient();

    List<Device> findByClient(Long clientId);

    Optional<Device> findByIdAndClient(Long id, Long clientId);

    List<Device> findByClientAndStatus(Long clientId, String status);

    List<Device> findByStatus(String status);

    List<Device> findByZone(Long plantZoneId);

    long countByZone(Long plantZoneId);

    List<Device> findOnline();

    boolean existsById(Long id);

    void deleteById(Long id);
}
