package com.emsafe.aiforecasting.domain.model;

import java.util.Comparator;

/**
 * Orden de urgencia de las evaluaciones: criticidad descendente, luego excedencia estimada
 * ascendente (sin excedencia al final) y, para que sea determinista, menor workerId.
 */
public final class AssessmentPriority {

    public static final Comparator<PreventiveAssessment> MOST_URGENT_FIRST =
            Comparator.comparing(PreventiveAssessment::getCriticality,
                            Comparator.nullsLast(Comparator.<CriticalityLevel>reverseOrder()))
                    .thenComparing(a -> a.getProjection().estimatedExceedanceAt(),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(PreventiveAssessment::getWorkerId);

    private AssessmentPriority() {
    }
}
