package com.emsafe.occupationalhealth.infrastructure.persistence;

import com.emsafe.occupationalhealth.domain.model.ClosedSessionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Proyección de jornadas consolidadas (informe §5.6.4). */
public interface ClosedSessionRecordRepository extends JpaRepository<ClosedSessionRecord, Long> {

    Optional<ClosedSessionRecord> findByOrganizationIdAndSessionId(Long organizationId,
                                                                   String sessionId);

    /**
     * Jornadas de un operario dentro del período. Es la consulta que sostiene la
     * generación de reportes, y el índice
     * (organization_id, worker_id, finalized_at) de V14 está para ella.
     */
    @Query("SELECT s FROM ClosedSessionRecord s"
            + " WHERE s.organizationId = :organizationId AND s.workerId = :workerId"
            + " AND s.finalizedAt BETWEEN :from AND :to"
            + " ORDER BY s.finalizedAt ASC")
    List<ClosedSessionRecord> findByWorkerAndPeriod(@Param("organizationId") Long organizationId,
                                                    @Param("workerId") Long workerId,
                                                    @Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to);

    /** Para RetentionJob: lo que ya pasó su plazo de conservación. */
    List<ClosedSessionRecord> findByFinalizedAtBefore(LocalDateTime threshold);
}
