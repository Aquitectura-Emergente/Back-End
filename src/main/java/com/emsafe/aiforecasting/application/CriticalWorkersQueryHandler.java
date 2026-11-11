package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.AssessmentPriority;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.PageResult;
import com.emsafe.shared.exception.BadRequestException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Evaluaciones activas de una organización, de la más urgente a la menos urgente. */
public class CriticalWorkersQueryHandler {

    private final AssessmentRepository assessments;

    public CriticalWorkersQueryHandler(AssessmentRepository assessments) {
        this.assessments = assessments;
    }

    @Transactional(readOnly = true)
    public PageResult<PreventiveAssessment> list(Long organizationId, int page, int size) {
        if (organizationId == null) {
            throw new BadRequestException("organizationId is required");
        }
        ForecastingPaging.validate(page, size);
        List<PreventiveAssessment> sorted = assessments.findActiveByOrganizationId(organizationId).stream()
                .sorted(AssessmentPriority.MOST_URGENT_FIRST)
                .toList();
        return new PageResult<>(ForecastingPaging.slice(sorted, page, size), page, size, sorted.size());
    }
}
