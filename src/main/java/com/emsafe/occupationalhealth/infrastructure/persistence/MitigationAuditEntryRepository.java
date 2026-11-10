package com.emsafe.occupationalhealth.infrastructure.persistence;

import com.emsafe.occupationalhealth.domain.model.MitigationAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MitigationAuditEntryRepository extends JpaRepository<MitigationAuditEntry, Long> {

    /** Para RetentionJob: por plazo y por resultado, que tienen plazos distintos. */
    List<MitigationAuditEntry> findByOutcomeAndOccurredAtBefore(String outcome,
                                                                 LocalDateTime threshold);
}
