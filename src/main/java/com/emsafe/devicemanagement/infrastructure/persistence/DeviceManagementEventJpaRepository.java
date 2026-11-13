package com.emsafe.devicemanagement.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceManagementEventJpaRepository extends JpaRepository<DeviceManagementEvent, String> {
    List<DeviceManagementEvent> findByPublishedAtIsNullOrderByOccurredAtAsc(Pageable pageable);

    List<DeviceManagementEvent> findByEventTypeOrderByOccurredAtAsc(String eventType);
}
