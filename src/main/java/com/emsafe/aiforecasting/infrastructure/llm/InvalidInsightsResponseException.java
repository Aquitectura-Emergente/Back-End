package com.emsafe.aiforecasting.infrastructure.llm;

/** La respuesta del LLM al análisis del periodo no cumple la estructura o las longitudes pedidas. */
class InvalidInsightsResponseException extends RuntimeException {

    InvalidInsightsResponseException(String message) {
        super(message);
    }
}
