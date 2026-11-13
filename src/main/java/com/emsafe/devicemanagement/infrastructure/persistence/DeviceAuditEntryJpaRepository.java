package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.DeviceAuditEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceAuditEntryJpaRepository extends JpaRepository<DeviceAuditEntry, Long> {
    Page<DeviceAuditEntry> findByDeviceIdOrderByOccurredAtDescIdDesc(Long deviceId, Pageable pageable);

    long countByDeviceId(Long deviceId);
}
