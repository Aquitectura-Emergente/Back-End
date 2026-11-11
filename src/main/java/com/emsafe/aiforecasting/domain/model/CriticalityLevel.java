package com.emsafe.aiforecasting.domain.model;

/** Prioridad preventiva de una evaluación, declarada de menor a mayor severidad. */
public enum CriticalityLevel {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL;

    public boolean isMoreSevereThan(CriticalityLevel other) {
        return compareTo(other) > 0;
    }

    /** Inclusivo; {@code null} significa "sin filtro". */
    public boolean isAtLeast(CriticalityLevel minimum) {
        return minimum == null || compareTo(minimum) >= 0;
    }

    /** Solo HIGH y CRITICAL generan recomendación y eventos de riesgo. */
    public boolean requiresRecommendation() {
        return this == HIGH || this == CRITICAL;
    }
}
