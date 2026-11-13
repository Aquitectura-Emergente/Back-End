package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Construye los eventos salientes de una evaluación con el sobre común
 * (eventId, schemaVersion, organizationId, occurredAt, correlationId, causationId).
 */
public class ForecastingEventFactory {

    public static final String ROTATION_SUGGESTED = "RotationSuggested";
    public static final String PREDICTIVE_RISK_DETECTED = "PredictiveRiskDetected";

    public List<OutboxEvent> eventsFor(PreventiveAssessment assessment, EventCause cause, Instant occurredAt) {
        List<OutboxEvent> events = new ArrayList<>(2);
        assessment.getRecommendation()
                .ifPresent(r -> events.add(envelope(ROTATION_SUGGESTED, assessment, cause, occurredAt,
                        rotationSuggested(assessment, r))));
        if (assessment.getCriticality().requiresRecommendation()) {
            events.add(envelope(PREDICTIVE_RISK_DETECTED, assessment, cause, occurredAt,
                    predictiveRiskDetected(assessment)));
        }
        return events;
    }

    /**
     * Cuerpo plano compatible con {@code RotationSuggestedInput} de Occupational Health.
     * {@code projectedExceedanceAt} va como LocalDateTime en UTC, sin zona, porque ese inbox
     * lo recibe como {@code LocalDateTime}.
     */
    private static Map<String, Object> rotationSuggested(PreventiveAssessment a, RotationRecommendation r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", a.getOrganizationId());
        payload.put("workerId", a.getWorkerId());
        payload.put("suggestionId", a.getId());
        payload.put("candidateWorkerIds",
                r.type() == RecommendationType.ROTATION ? List.of(r.targetWorkerId()) : List.of());
        payload.put("projectedExceedanceAt", utcLocalDateTime(a.getProjection().estimatedExceedanceAt()));
        payload.put("criticality", a.getCriticality().name());
        payload.put("rationale", a.getRedaction().map(n -> n.text()).orElse(r.reason()));
        return payload;
    }

    private static Map<String, Object> predictiveRiskDetected(PreventiveAssessment a) {
        ExposureProjection p = a.getProjection();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", a.getOrganizationId());
        payload.put("assessmentId", a.getId());
        payload.put("workerId", a.getWorkerId());
        payload.put("criticality", a.getCriticality().name());
        payload.put("estimatedExceedanceAt",
                p.estimatedExceedanceAt() == null ? null : p.estimatedExceedanceAt().toString());
        payload.put("projectedIndex", p.projectedIndexAtShiftEnd());
        payload.put("limitIndex", p.limitIndex());
        payload.put("isProjection", true);
        return payload;
    }

    private static OutboxEvent envelope(String type, PreventiveAssessment a, EventCause cause, Instant occurredAt,
                                        Map<String, Object> payload) {
        return new OutboxEvent(UUID.randomUUID().toString(), type, OutboxEvent.SCHEMA_VERSION,
                a.getOrganizationId(), a.getId(), occurredAt, cause.correlationId(), cause.eventId(), payload, null);
    }

    private static String utcLocalDateTime(Instant instant) {
        return instant == null ? null
                : LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
