package com.emsafe.occupationalhealth.infrastructure.persistence;

import com.emsafe.occupationalhealth.domain.model.MitigationAction;
import com.emsafe.occupationalhealth.domain.model.MitigationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Acciones de mitigación y su historial (informe §5.6.1,
 * MitigationActionRepository).
 */
public interface MitigationActionRepository extends JpaRepository<MitigationAction, Long> {

    @Query("SELECT a FROM MitigationAction a LEFT JOIN FETCH a.affectedWorkers"
            + " WHERE a.id = :id AND a.organizationId = :organizationId")
    Optional<MitigationAction> findByIdAndOrganization(@Param("id") Long id,
                                                       @Param("organizationId") Long organizationId);

    /**
     * La bandeja del SSO. Ordena por prioridad antes que por fecha para que las
     * acciones escaladas salgan primero (regla 10); HIGH precede a NORMAL en el
     * orden del enum, de ahí el ASC.
     */
    @Query("SELECT DISTINCT a FROM MitigationAction a LEFT JOIN FETCH a.affectedWorkers"
            + " WHERE a.organizationId = :organizationId"
            + " AND (:state IS NULL OR a.state = :state)"
            + " ORDER BY a.priority ASC, a.createdAt DESC")
    List<MitigationAction> findByState(@Param("organizationId") Long organizationId,
                                       @Param("state") MitigationState state);

    /**
     * Acciones vivas que compiten por un operario. Sostiene la regla 5: una
     * propuesta nueva desplaza a la pendiente del mismo operario.
     */
    @Query("SELECT DISTINCT a FROM MitigationAction a JOIN a.affectedWorkers w"
            + " LEFT JOIN FETCH a.affectedWorkers"
            + " WHERE a.organizationId = :organizationId AND w.workerId = :workerId"
            + " AND a.state IN (com.emsafe.occupationalhealth.domain.model.MitigationState.PROPOSED,"
            + "                 com.emsafe.occupationalhealth.domain.model.MitigationState.APPROVED)"
            + " AND a.type <> com.emsafe.occupationalhealth.domain.model.MitigationType.RESCHEDULING")
    List<MitigationAction> findOpenByWorker(@Param("organizationId") Long organizationId,
                                            @Param("workerId") Long workerId);

    @Query("SELECT DISTINCT a FROM MitigationAction a JOIN a.affectedWorkers w"
            + " LEFT JOIN FETCH a.affectedWorkers"
            + " WHERE a.organizationId = :organizationId AND w.workerId = :workerId"
            + " AND a.createdAt BETWEEN :from AND :to"
            + " ORDER BY a.createdAt DESC")
    List<MitigationAction> findByWorkerAndPeriod(@Param("organizationId") Long organizationId,
                                                 @Param("workerId") Long workerId,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    /**
     * Inbox mínimo: descarta una excedencia o sugerencia ya procesada. El UNIQUE
     * de V13 es la red definitiva, pero comprobarlo antes evita una excepción de
     * integridad en el camino normal.
     */
    @Query("SELECT COUNT(a) > 0 FROM MitigationAction a"
            + " WHERE a.organizationId = :organizationId"
            + " AND a.trigger.sourceEventId = :sourceEventId")
    boolean existsBySourceEvent(@Param("organizationId") Long organizationId,
                                @Param("sourceEventId") String sourceEventId);

    /** Para RetentionJob: acciones que superaron su plazo de conservación. */
    List<MitigationAction> findByCreatedAtBefore(LocalDateTime threshold);
}
