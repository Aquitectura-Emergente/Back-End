package com.emsafe.aiforecasting.application;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/**
 * Evento entrante que causa los eventos salientes: su eventId es el {@code causationId}.
 * Si no trae correlationId, la cadena se correlaciona con su propio eventId.
 */
public record EventCause(String eventId, String correlationId) {

    public EventCause {
        requireText(eventId, "eventId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = eventId;
        }
    }
}
