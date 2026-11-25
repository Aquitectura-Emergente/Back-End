package com.emsafe.personaldosimetry.infrastructure.persistence;

import com.emsafe.personaldosimetry.domain.model.ExposureSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.Optional;

public interface ExposureSessionRepository extends JpaRepository<ExposureSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExposureSession s where s.id = :id")
    Optional<ExposureSession> findLocked(@Param("id") String id);

    @Query("""
            select count(s) > 0 from ExposureSession s
            where (s.workerId = :workerId or s.deviceId = :deviceId)
              and (s.endedAt is null or s.endedAt > :startedAt)
            """)
    boolean hasConflictingAssignment(@Param("workerId") Long workerId, @Param("deviceId") Long deviceId,
                                     @Param("startedAt") Instant startedAt);

    Optional<ExposureSession> findFirstByWorkerIdOrderByStartedAtDesc(Long workerId);

    /** La jornada abierta de un operario en su organización (la usa ExposureQueryFacade). */
    Optional<ExposureSession> findFirstByOrganizationIdAndWorkerIdAndStateOrderByStartedAtDesc(
            Long organizationId, Long workerId, ExposureSession.State state);

    /** Jornadas del dosímetro que cubren un instante (la más reciente primero). */
    @Query("""
            select s.id from ExposureSession s
            where s.deviceId = :deviceId and s.startedAt <= :at and (s.endedAt is null or s.endedAt > :at)
            order by s.startedAt desc
            """)
    java.util.List<String> findSessionIdsCovering(@Param("deviceId") Long deviceId, @Param("at") Instant at,
                                                  org.springframework.data.domain.Pageable page);
    Page<ExposureSession> findByOrganizationIdAndWorkerIdOrderByStartedAtDesc(
            Long organizationId, Long workerId, Pageable pageable);

    /**
     * Jornadas de la organización en un estado dado cuyo {@code endedAt} cae en
     * {@code [from, to)}, de la que terminó antes a la última (la usa
     * ExposureQueryFacade.closedSessions).
     */
    @Query("""
            select s from ExposureSession s
            where s.organizationId = :organizationId and s.state = :state
              and s.endedAt >= :from and s.endedAt < :to
            order by s.endedAt asc, s.id asc
            """)
    java.util.List<ExposureSession> findEndedBetween(@Param("organizationId") Long organizationId,
                                                     @Param("state") ExposureSession.State state,
                                                     @Param("from") Instant from, @Param("to") Instant to);
}
