package com.emsafe.aiforecasting.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data de {@code af_outbox}. Solo lo usa {@link JpaForecastingOutboxAdapter}. */
public interface OutboxJpaRepository extends JpaRepository<OutboxEventEntity, Long> {

    Optional<OutboxEventEntity> findByEventId(String eventId);

    Page<OutboxEventEntity> findByPublishedAtIsNullOrderByOccurredAtAscIdAsc(Pageable pageable);
}
