package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.AssessmentPriority;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.PageResult;
import com.emsafe.shared.exception.BadRequestException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Evaluaciones vigentes de una organización (jornadas que no terminaron, una por operario; ver
 * {@link OpenSessionAssessments}), de la más urgente a la menos urgente.
 */
public class CriticalWorkersQueryHandler {

    private final OpenSessionAssessments assessments;

    public CriticalWorkersQueryHandler(OpenSessionAssessments assessments) {
        this.assessments = assessments;
    }

    public PageResult<PreventiveAssessment> list(Long organizationId, int page, int size) {
        return list(organizationId, null, page, size);
    }

    /** @param minCriticality criticidad mínima (inclusiva); {@code null} = sin filtro */
    @Transactional(readOnly = true)
    public PageResult<PreventiveAssessment> list(Long organizationId, CriticalityLevel minCriticality,
                                                 int page, int size) {
        if (organizationId == null) {
            throw new BadRequestException("organizationId is required");
        }
        ForecastingPaging.validate(page, size);
        List<PreventiveAssessment> sorted = assessments.current(organizationId).stream()
                .filter(a -> a.getCriticality() != null && a.getCriticality().isAtLeast(minCriticality))
                .sorted(AssessmentPriority.MOST_URGENT_FIRST)
                .toList();
        return new PageResult<>(ForecastingPaging.slice(sorted, page, size), page, size, sorted.size());
    }
}
