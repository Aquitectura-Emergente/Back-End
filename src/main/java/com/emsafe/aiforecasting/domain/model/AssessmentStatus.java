package com.emsafe.aiforecasting.domain.model;

/**
 * Ciclo de vida de una {@link PreventiveAssessment}:
 * {@code PROJECTED → ASSESSED → RECOMMENDED → CLOSED}, y también {@code ASSESSED → CLOSED}.
 */
public enum AssessmentStatus {
    PROJECTED,
    ASSESSED,
    RECOMMENDED,
    CLOSED;

    public boolean canTransitionTo(AssessmentStatus target) {
        return switch (this) {
            case PROJECTED -> target == ASSESSED;
            case ASSESSED -> target == RECOMMENDED || target == CLOSED;
            case RECOMMENDED -> target == CLOSED;
            case CLOSED -> false;
        };
    }
}
