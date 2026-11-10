package com.emsafe.occupationalhealth.infrastructure.persistence;

import com.emsafe.occupationalhealth.domain.model.ComplianceReport;
import com.emsafe.occupationalhealth.domain.model.ReportScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reportes de cumplimiento (informe §5.6.1, ComplianceReportRepository).
 *
 * La interfaz del informe es findById(), nextVersionFor() y append() —sin
 * update— para dejar claro que un reporte no se sobrescribe. Aquí se hereda
 * JpaRepository, que sí trae save(); la inmutabilidad la sostiene el agregado,
 * que no expone setters, y no la firma del repositorio.
 */
public interface ComplianceReportRepository extends JpaRepository<ComplianceReport, Long> {

    @Query("SELECT r FROM ComplianceReport r LEFT JOIN FETCH r.entries"
            + " WHERE r.id = :id AND r.organizationId = :organizationId")
    Optional<ComplianceReport> findByIdAndOrganization(@Param("id") Long id,
                                                       @Param("organizationId") Long organizationId);

    /**
     * La versión más alta ya emitida para ese alcance, sujeto y período.
     *
     * Devuelve null cuando no hay ninguna; el handler lo traduce a 1. La
     * comparación de subjectWorkerId usa un IS NULL explícito porque en los
     * reportes por zona es nulo y `= null` nunca sería cierto.
     */
    @Query("SELECT MAX(r.version) FROM ComplianceReport r"
            + " WHERE r.organizationId = :organizationId AND r.scope = :scope"
            + " AND (:workerId IS NULL AND r.subjectWorkerId IS NULL"
            + "      OR r.subjectWorkerId = :workerId)"
            + " AND r.period.from = :from AND r.period.to = :to")
    Integer findMaxVersion(@Param("organizationId") Long organizationId,
                           @Param("scope") ReportScope scope,
                           @Param("workerId") Long workerId,
                           @Param("from") LocalDate from,
                           @Param("to") LocalDate to);

    @Query("SELECT r FROM ComplianceReport r"
            + " WHERE r.organizationId = :organizationId"
            + " AND (:workerId IS NULL OR r.subjectWorkerId = :workerId)"
            + " ORDER BY r.generatedAt DESC")
    List<ComplianceReport> findByWorker(@Param("organizationId") Long organizationId,
                                        @Param("workerId") Long workerId);

    /** Para RetentionJob: reportes que superaron su plazo de conservación. */
    List<ComplianceReport> findByGeneratedAtBefore(java.time.LocalDateTime threshold);
}
