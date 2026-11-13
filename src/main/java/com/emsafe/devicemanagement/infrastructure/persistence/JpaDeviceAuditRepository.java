package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.DeviceAuditEntry;
import com.emsafe.devicemanagement.domain.repositories.DeviceAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaDeviceAuditRepository implements DeviceAuditRepository {
    private final DeviceAuditEntryJpaRepository entries;

    @Override
    public List<DeviceAuditEntry> findByDevice(Long deviceId, int page, int size) {
        return entries.findByDeviceIdOrderByOccurredAtDescIdDesc(deviceId, PageRequest.of(page, size)).getContent();
    }

    @Override
    public long countByDevice(Long deviceId) {
        return entries.countByDeviceId(deviceId);
    }
}
