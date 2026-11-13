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
}
