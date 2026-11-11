package com.emsafe.personaldosimetry.infrastructure.persistence;

import com.emsafe.personaldosimetry.domain.model.DosimetryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DosimetryEventRepository extends JpaRepository<DosimetryEvent, String> {
    Page<DosimetryEvent> findByPublishedAtIsNullOrderByOccurredAtAsc(Pageable pageable);
}
