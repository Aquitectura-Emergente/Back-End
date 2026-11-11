package com.emsafe.personaldosimetry.infrastructure.persistence;

import com.emsafe.personaldosimetry.domain.model.DosimetryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.List;

public interface DosimetryEventRepository extends JpaRepository<DosimetryEvent, String> {
    Page<DosimetryEvent> findByPublishedAtIsNullOrderByOccurredAtAsc(Pageable pageable);
    List<DosimetryEvent> findByPublishedAtBeforeOrderByPublishedAtAscIdAsc(Instant cutoff, Pageable pageable);
}
