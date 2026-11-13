package com.emsafe.plantconfiguration.infrastructure.persistence;

import com.emsafe.plantconfiguration.domain.model.PlantEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlantEdgeRepository extends JpaRepository<PlantEdge, Long> {

    Optional<PlantEdge> findByPlantZoneId(Long plantZoneId);

    List<PlantEdge> findByPlantZoneIdIn(List<Long> plantZoneIds);

    boolean existsByPlantZoneId(Long plantZoneId);

    boolean existsBySerialNumber(String serialNumber);

    Optional<PlantEdge> findBySerialNumber(String serialNumber);

    boolean existsBySerialNumberAndIdNot(String serialNumber, Long id);
}
