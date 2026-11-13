package com.emsafe.plantconfiguration.infrastructure.persistence;

import com.emsafe.plantconfiguration.domain.model.JobPosition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobPositionRepository extends JpaRepository<JobPosition, Long> {

    List<JobPosition> findByPlantZoneIdOrderByNameAsc(Long plantZoneId);

    List<JobPosition> findByPlantZoneIdInOrderByNameAsc(List<Long> plantZoneIds);

    boolean existsByPlantZoneIdAndName(Long plantZoneId, String name);

    boolean existsByPlantZoneIdAndNameAndIdNot(Long plantZoneId, String name, Long id);

    long countByPlantZoneId(Long plantZoneId);
}
