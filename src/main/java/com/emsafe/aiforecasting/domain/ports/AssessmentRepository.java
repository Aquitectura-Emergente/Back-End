package com.emsafe.aiforecasting.domain.ports;

import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;

import java.util.List;
import java.util.Optional;

/** Persistencia del agregado {@link PreventiveAssessment}. */
public interface AssessmentRepository {

    PreventiveAssessment save(PreventiveAssessment assessment);

    Optional<PreventiveAssessment> findById(String id);

    /** Evaluación no cerrada de la sesión; como máximo hay una. */
    Optional<PreventiveAssessment> findActiveBySessionId(String sessionId);

    /** Evaluación activa más reciente del operario. */
    Optional<PreventiveAssessment> findActiveByWorkerId(Long workerId);

    /** Evaluaciones no cerradas de una organización. */
    List<PreventiveAssessment> findActiveByOrganizationId(Long organizationId);

    PageResult<PreventiveAssessment> search(AssessmentSearchCriteria criteria, int page, int size);
}
