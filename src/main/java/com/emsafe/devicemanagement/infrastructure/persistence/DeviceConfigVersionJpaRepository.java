package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.DeviceConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceConfigVersionJpaRepository extends JpaRepository<DeviceConfigVersion, Long> {
    List<DeviceConfigVersion> findByDeviceIdOrderByConfigVersionAsc(Long deviceId);
}
