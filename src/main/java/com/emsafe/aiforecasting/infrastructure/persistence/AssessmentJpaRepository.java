package com.emsafe.aiforecasting.infrastructure.persistence;

import com.emsafe.aiforecasting.domain.model.AssessmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/** Spring Data de {@code af_preventive_assessment}. Solo lo usa {@link JpaAssessmentRepositoryAdapter}. */
public interface AssessmentJpaRepository extends JpaRepository<PreventiveAssessmentEntity, String>,
        JpaSpecificationExecutor<PreventiveAssessmentEntity> {

    Optional<PreventiveAssessmentEntity> findByActiveSessionKey(String sessionId);

    Optional<PreventiveAssessmentEntity> findFirstByWorkerIdAndStatusNotOrderByAssessedAtDesc(
            Long workerId, AssessmentStatus status);

    List<PreventiveAssessmentEntity> findByOrganizationIdAndStatusNot(Long organizationId, AssessmentStatus status);
}
