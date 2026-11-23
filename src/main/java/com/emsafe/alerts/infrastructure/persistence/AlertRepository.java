package com.emsafe.alerts.infrastructure.persistence;

import com.emsafe.alerts.domain.model.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findAllByOrderByCreatedAtDesc();

    long countByType(String type);

    /** Alertas de un operario, las más recientes primero (GET /api/alarms/mine). */
    List<Alert> findByWorkerIdOrderByCreatedAtDesc(Long workerId);

    /** Alertas de operario de una organización (GET /api/alarms/organization, panel del SSO). */
    List<Alert> findByOrganizationIdAndWorkerIdIsNotNullOrderByCreatedAtDesc(Long organizationId);

    /**
     * Alertas de operario de un tipo que siguen sin acuse ni escalamiento y se
     * crearon dentro de la ventana [{@code after}, {@code before}]. Las usa el
     * escalamiento automático al SSO (AlertEscalationJob de Occupational Health).
     */
    @Query("SELECT a FROM Alert a WHERE a.workerId IS NOT NULL AND a.organizationId IS NOT NULL"
            + " AND a.type = :type AND a.resolved = false"
            + " AND a.acknowledgedAt IS NULL AND a.escalatedAt IS NULL"
            + " AND a.createdAt BETWEEN :after AND :before ORDER BY a.createdAt ASC")
    List<Alert> findAwaitingEscalation(@Param("type") String type,
                                       @Param("after") LocalDateTime after,
                                       @Param("before") LocalDateTime before);
}
