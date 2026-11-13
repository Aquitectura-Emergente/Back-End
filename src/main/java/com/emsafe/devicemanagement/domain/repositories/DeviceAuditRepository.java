package com.emsafe.devicemanagement.domain.repositories;

import com.emsafe.devicemanagement.domain.model.DeviceAuditEntry;

import java.util.List;

/** Lectura de la auditoría; las entradas las escribe {@link DeviceRepository}. */
public interface DeviceAuditRepository {
    List<DeviceAuditEntry> findByDevice(Long deviceId, int page, int size);

    long countByDevice(Long deviceId);
}
