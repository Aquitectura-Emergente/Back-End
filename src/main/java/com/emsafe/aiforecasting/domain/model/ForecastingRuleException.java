package com.emsafe.aiforecasting.domain.model;

/**
 * Violación de una regla del contexto AI Forecasting.
 * {@link Kind#INVALID_RULE} se traduce a 422 y {@link Kind#CONFLICT} a 409.
 */
public class ForecastingRuleException extends RuntimeException {

    public enum Kind { INVALID_RULE, CONFLICT }

    private final Kind kind;

    private ForecastingRuleException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public static ForecastingRuleException invalid(String message) {
        return new ForecastingRuleException(Kind.INVALID_RULE, message);
    }

    public static ForecastingRuleException conflict(String message) {
        return new ForecastingRuleException(Kind.CONFLICT, message);
    }

    public static <T> T requirePresent(T value, String field) {
        if (value == null) {
            throw invalid(field + " is required");
        }
        return value;
    }

    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        return value;
    }
}
