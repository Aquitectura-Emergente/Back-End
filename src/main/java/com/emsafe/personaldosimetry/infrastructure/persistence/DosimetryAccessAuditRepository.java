package com.emsafe.personaldosimetry.infrastructure.persistence;

import com.emsafe.personaldosimetry.domain.model.DosimetryAccessAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface DosimetryAccessAuditRepository extends JpaRepository<DosimetryAccessAudit, String> {
    Page<DosimetryAccessAudit> findAllByOrderByOccurredAtDescIdDesc(Pageable pageable);
    List<DosimetryAccessAudit> findByOccurredAtBeforeOrderByOccurredAtAscIdAsc(Instant cutoff, Pageable pageable);
}
