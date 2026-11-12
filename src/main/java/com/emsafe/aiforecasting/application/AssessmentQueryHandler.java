package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.AssessmentSearchCriteria;
import com.emsafe.aiforecasting.domain.ports.PageResult;
import com.emsafe.shared.exception.ResourceNotFoundException;
import org.springframework.transaction.annotation.Transactional;

/** Consultas de evaluaciones. El control de acceso por organización va en ForecastingAccessPolicy (fase 4). */
public class AssessmentQueryHandler {

    private final AssessmentRepository assessments;

    public AssessmentQueryHandler(AssessmentRepository assessments) {
        this.assessments = assessments;
    }

    @Transactional(readOnly = true)
    public PreventiveAssessment getById(String id) {
        return assessments.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found: " + id));
    }

    @Transactional(readOnly = true)
    public PreventiveAssessment activeForWorker(Long workerId) {
        return assessments.findActiveByWorkerId(workerId)
                .orElseThrow(() -> new ResourceNotFoundException("No active assessment for worker " + workerId));
    }

    @Transactional(readOnly = true)
    public PageResult<PreventiveAssessment> search(AssessmentSearchCriteria criteria, int page, int size) {
        ForecastingPaging.validate(page, size);
        return assessments.search(criteria, page, size);
    }
}
