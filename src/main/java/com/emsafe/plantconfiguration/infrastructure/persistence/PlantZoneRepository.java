package com.emsafe.plantconfiguration.infrastructure.persistence;

import com.emsafe.plantconfiguration.domain.model.PlantZone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlantZoneRepository extends JpaRepository<PlantZone, Long> {

    List<PlantZone> findByOrganizationIdOrderByNameAsc(Long organizationId);

    Optional<PlantZone> findByIdAndOrganizationId(Long id, Long organizationId);

    boolean existsByOrganizationIdAndName(Long organizationId, String name);

    boolean existsByOrganizationIdAndNameAndIdNot(Long organizationId, String name, Long id);
}
