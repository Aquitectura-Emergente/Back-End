package com.emsafe.occupationalhealth.infrastructure.persistence;

import com.emsafe.occupationalhealth.domain.model.AlertEvidenceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Proyección de acuses y escalamientos de alertas (informe §5.6.4). */
public interface AlertEvidenceRecordRepository extends JpaRepository<AlertEvidenceRecord, Long> {

    Optional<AlertEvidenceRecord> findByOrganizationIdAndAlertId(Long organizationId,
                                                                  String alertId);

    @Query("SELECT a FROM AlertEvidenceRecord a"
            + " WHERE a.organizationId = :organizationId AND a.workerId = :workerId")
    List<AlertEvidenceRecord> findByWorker(@Param("organizationId") Long organizationId,
                                           @Param("workerId") Long workerId);

    /**
     * Para RetentionJob. La tabla no tiene fecha de creación: su antigüedad se
     * mide por la marca más reciente que tenga, y si no tiene ninguna no se
     * borra, porque no hay forma de saber de cuándo es.
     */
    @Query("SELECT a FROM AlertEvidenceRecord a"
            + " WHERE COALESCE(a.escalatedAt, a.acknowledgedAt) < :threshold")
    List<AlertEvidenceRecord> findOlderThan(
            @Param("threshold") java.time.LocalDateTime threshold);
}
