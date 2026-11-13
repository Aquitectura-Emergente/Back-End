package com.emsafe.personaldosimetry.infrastructure.persistence;

import com.emsafe.personaldosimetry.domain.model.DosimetryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DosimetryEventRepository extends JpaRepository<DosimetryEvent, String> {
    Page<DosimetryEvent> findByPublishedAtIsNullOrderByOccurredAtAsc(Pageable pageable);
    List<DosimetryEvent> findByPublishedAtBeforeOrderByPublishedAtAscIdAsc(Instant cutoff, Pageable pageable);

    /**
     * Pendientes de los tipos dados, para {@code DosimetryOutboxRelay}.
     *
     * <p>Hace falta filtrar por tipo y no reutilizar el método de arriba porque
     * de los cinco tipos que emite este contexto solo dos tienen hoy destino. Los
     * otros se quedan pendientes a propósito —marcarlos perdería el hecho—, así
     * que encabezarían la cola para siempre y taparían los entregables.</p>
     */
    Page<DosimetryEvent> findByPublishedAtIsNullAndEventTypeInOrderByOccurredAtAsc(
            Collection<String> eventTypes, Pageable pageable);
}
