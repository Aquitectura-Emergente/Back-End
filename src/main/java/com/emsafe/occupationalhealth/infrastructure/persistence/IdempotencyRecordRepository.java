package com.emsafe.occupationalhealth.infrastructure.persistence;

import com.emsafe.occupationalhealth.domain.model.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByOrganizationIdAndIdempotencyKeyAndEndpoint(
            Long organizationId, String idempotencyKey, String endpoint);

    List<IdempotencyRecord> findByCreatedAtBefore(LocalDateTime threshold);
}
