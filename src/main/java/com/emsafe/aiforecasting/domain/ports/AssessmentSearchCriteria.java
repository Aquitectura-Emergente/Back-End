package com.emsafe.aiforecasting.domain.ports;

import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;

import java.time.Instant;

/** Filtros opcionales de búsqueda de evaluaciones; {@code from}/{@code to} aplican a assessedAt. */
public record AssessmentSearchCriteria(Long organizationId, Long workerId, String sessionId,
                                       Instant from, Instant to) {

    public AssessmentSearchCriteria {
        if (from != null && to != null && from.isAfter(to)) {
            throw ForecastingRuleException.invalid("from must not be after to");
        }
    }
}
