package com.emsafe.fieldservice.infrastructure.persistence;

import com.emsafe.fieldservice.domain.model.Sensor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SensorRepository extends JpaRepository<Sensor, Long> {

    List<Sensor> findByWorkOrderId(Long workOrderId);

    long countByWorkOrderId(Long workOrderId);
}
