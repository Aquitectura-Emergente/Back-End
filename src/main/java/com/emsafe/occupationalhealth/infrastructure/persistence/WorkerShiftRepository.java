package com.emsafe.occupationalhealth.infrastructure.persistence;

import com.emsafe.occupationalhealth.domain.model.ShiftState;
import com.emsafe.occupationalhealth.domain.model.WorkerShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Turnos del operario (informe §5.6.1, WorkerShiftRepository).
 *
 * Toda consulta filtra por organización: es lo que sustituye al aislamiento que
 * el informe lograba con la PK compuesta (ver V13).
 */
public interface WorkerShiftRepository extends JpaRepository<WorkerShift, Long> {

    /**
     * El turno vigente del operario. Solo puede haber uno: lo garantiza el
     * índice único sobre la columna generada active_worker_id.
     */
    @Query("SELECT s FROM WorkerShift s LEFT JOIN FETCH s.assignments"
            + " WHERE s.organizationId = :organizationId AND s.workerId = :workerId"
            + " AND s.state = :state")
    Optional<WorkerShift> findActiveByWorker(@Param("organizationId") Long organizationId,
                                             @Param("workerId") Long workerId,
                                             @Param("state") ShiftState state);

    default Optional<WorkerShift> findActiveByWorker(Long organizationId, Long workerId) {
        return findActiveByWorker(organizationId, workerId, ShiftState.ACTIVE);
    }

    @Query("SELECT DISTINCT s FROM WorkerShift s LEFT JOIN FETCH s.assignments"
            + " WHERE s.organizationId = :organizationId AND s.workerId = :workerId"
            + " AND s.shiftDate BETWEEN :from AND :to"
            + " ORDER BY s.shiftDate DESC")
    List<WorkerShift> findByWorkerAndPeriod(@Param("organizationId") Long organizationId,
                                            @Param("workerId") Long workerId,
                                            @Param("from") LocalDate from,
                                            @Param("to") LocalDate to);

    @Query("SELECT s FROM WorkerShift s LEFT JOIN FETCH s.assignments"
            + " WHERE s.id = :id AND s.organizationId = :organizationId")
    Optional<WorkerShift> findByIdAndOrganization(@Param("id") Long id,
                                                  @Param("organizationId") Long organizationId);

    /** Para RetentionJob: turnos que superaron su plazo de conservación. */
    List<WorkerShift> findByCreatedAtBefore(java.time.LocalDateTime threshold);
}
