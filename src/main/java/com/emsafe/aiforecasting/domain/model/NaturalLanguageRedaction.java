package com.emsafe.aiforecasting.domain.model;

import java.time.Instant;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;
import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/** Explicación en lenguaje natural de una evaluación, generada por el LLM o por plantilla. */
public record NaturalLanguageRedaction(String text, RedactionSource generatedBy, Instant generatedAt) {

    public NaturalLanguageRedaction {
        requireText(text, "text");
        requirePresent(generatedBy, "generatedBy");
        requirePresent(generatedAt, "generatedAt");
    }
}
