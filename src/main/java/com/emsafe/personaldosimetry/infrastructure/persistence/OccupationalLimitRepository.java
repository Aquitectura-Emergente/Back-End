package com.emsafe.personaldosimetry.infrastructure.persistence;

import com.emsafe.personaldosimetry.domain.model.OccupationalLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Optional;

public interface OccupationalLimitRepository extends JpaRepository<OccupationalLimit, Long> {
    Optional<OccupationalLimit> findFirstByOrganizationIdAndJobPositionIdOrderByVersionNumberDesc(
            Long organizationId, Long jobPositionId);
    Optional<OccupationalLimit> findFirstByOrganizationIdAndJobPositionIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescVersionNumberDesc(
            Long organizationId, Long jobPositionId, Instant effectiveAt);
    Page<OccupationalLimit> findByOrganizationIdOrderByJobPositionIdAscVersionNumberDesc(
            Long organizationId, Pageable pageable);
}
