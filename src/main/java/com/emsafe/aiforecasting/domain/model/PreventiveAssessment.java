package com.emsafe.aiforecasting.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;
import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/**
 * Agregado raíz: evaluación preventiva de la exposición de un operario en una sesión.
 *
 * <p>Ciclo de vida {@code PROJECTED → ASSESSED → RECOMMENDED → CLOSED} (o {@code ASSESSED → CLOSED}).
 * Una transición inválida lanza {@link ForecastingRuleException}; una evaluación cerrada es inmutable.
 * Este contexto solo proyecta, prioriza y recomienda: nunca decide si el límite se excedió.</p>
 */
public class PreventiveAssessment {

    private final String id;
    private final Long organizationId;
    private final Long workerId;
    private final String sessionId;
    private final ExposureProjection projection;
    private final ModelVersion modelVersion;
    private final Long limitVersionId;
    private final String triggerEventId;
    private final Instant assessedAt;

    private CriticalityLevel criticality;
    private RotationRecommendation recommendation;
    private NaturalLanguageRedaction redaction;
    private AssessmentStatus status;
    private CloseReason closeReason;
    private Instant closedAt;

    private PreventiveAssessment(String id, Long organizationId, Long workerId, String sessionId,
                                 ExposureProjection projection, ModelVersion modelVersion,
                                 Long limitVersionId, String triggerEventId, Instant assessedAt) {
        this.id = requireUuid(id);
        this.organizationId = requirePresent(organizationId, "organizationId");
        this.workerId = requirePresent(workerId, "workerId");
        this.sessionId = requireText(sessionId, "sessionId");
        this.projection = requirePresent(projection, "projection");
        this.modelVersion = requirePresent(modelVersion, "modelVersion");
        this.limitVersionId = limitVersionId;
        this.triggerEventId = triggerEventId;
        this.assessedAt = requirePresent(assessedAt, "assessedAt");
    }

    /** Crea una evaluación nueva en estado PROJECTED. */
    public static PreventiveAssessment project(String id, Long organizationId, Long workerId, String sessionId,
                                               ExposureProjection projection, ModelVersion modelVersion,
                                               Long limitVersionId, String triggerEventId, Instant assessedAt) {
        PreventiveAssessment a = new PreventiveAssessment(id, organizationId, workerId, sessionId, projection,
                modelVersion, limitVersionId, triggerEventId, assessedAt);
        a.status = AssessmentStatus.PROJECTED;
        return a;
    }

    /** Rehidrata desde persistencia validando que el estado sea coherente. */
    public static PreventiveAssessment restore(String id, Long organizationId, Long workerId, String sessionId,
                                               ExposureProjection projection, CriticalityLevel criticality,
                                               RotationRecommendation recommendation,
                                               NaturalLanguageRedaction redaction, ModelVersion modelVersion,
                                               Long limitVersionId, AssessmentStatus status, CloseReason closeReason,
                                               String triggerEventId, Instant assessedAt, Instant closedAt) {
        PreventiveAssessment a = new PreventiveAssessment(id, organizationId, workerId, sessionId, projection,
                modelVersion, limitVersionId, triggerEventId, assessedAt);
        a.status = requirePresent(status, "status");
        a.criticality = criticality;
        a.recommendation = recommendation;
        a.redaction = redaction;
        a.closeReason = closeReason;
        a.closedAt = closedAt;
        a.checkRestoredState();
        return a;
    }

    // ─── Comportamiento ──────────────────────────────────────────────────────

    public void assess(CriticalityLevel level) {
        requirePresent(level, "criticality");
        transitionTo(AssessmentStatus.ASSESSED);
        this.criticality = level;
    }

    public void recommend(RotationRecommendation value) {
        requirePresent(value, "recommendation");
        ensureNotClosed();
        if (status == AssessmentStatus.ASSESSED && !criticality.requiresRecommendation()) {
            throw ForecastingRuleException.invalid(
                    "recommendations are only issued for HIGH or CRITICAL assessments");
        }
        if (!Objects.equals(value.sourceWorkerId(), workerId)) {
            throw ForecastingRuleException.invalid("recommendation must target the assessed worker");
        }
        transitionTo(AssessmentStatus.RECOMMENDED);
        this.recommendation = value;
    }

    /** Adjunta (o reemplaza) la redacción. Requiere criticidad ya evaluada. */
    public void attachRedaction(NaturalLanguageRedaction value) {
        requirePresent(value, "redaction");
        ensureNotClosed();
        if (status == AssessmentStatus.PROJECTED) {
            throw ForecastingRuleException.invalid("cannot redact an assessment before it is assessed");
        }
        this.redaction = value;
    }

    public void close(CloseReason reason, Instant at) {
        requirePresent(reason, "closeReason");
        requirePresent(at, "closedAt");
        ensureNotClosed();
        if (at.isBefore(assessedAt)) {
            throw ForecastingRuleException.invalid("closedAt cannot be before assessedAt");
        }
        transitionTo(AssessmentStatus.CLOSED);
        this.closeReason = reason;
        this.closedAt = at;
    }

    /** Cierre por un nuevo disparo de la misma sesión. */
    public void supersede(Instant at) {
        close(CloseReason.SUPERSEDED, at);
    }

    public boolean isActive() {
        return status != AssessmentStatus.CLOSED;
    }

    // ─── Invariantes ─────────────────────────────────────────────────────────

    private void transitionTo(AssessmentStatus target) {
        ensureNotClosed();
        if (!status.canTransitionTo(target)) {
            throw ForecastingRuleException.invalid(
                    "invalid assessment transition " + status + " -> " + target);
        }
        this.status = target;
    }

    private void ensureNotClosed() {
        if (status == AssessmentStatus.CLOSED) {
            throw ForecastingRuleException.invalid("assessment " + id + " is closed and cannot be modified");
        }
    }

    private void checkRestoredState() {
        if (status != AssessmentStatus.PROJECTED && criticality == null) {
            throw ForecastingRuleException.invalid("status " + status + " requires a criticality");
        }
        if (status == AssessmentStatus.RECOMMENDED && recommendation == null) {
            throw ForecastingRuleException.invalid("status RECOMMENDED requires a recommendation");
        }
        boolean closed = status == AssessmentStatus.CLOSED;
        if (closed != (closedAt != null) || closed != (closeReason != null)) {
            throw ForecastingRuleException.invalid("closedAt and closeReason must be set only when CLOSED");
        }
    }

    private static String requireUuid(String value) {
        requireText(value, "id");
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ForecastingRuleException.invalid("id must be a UUID");
        }
        return value;
    }

    // ─── Lectura ─────────────────────────────────────────────────────────────

    public String getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public Long getWorkerId() { return workerId; }
    public String getSessionId() { return sessionId; }
    public ExposureProjection getProjection() { return projection; }
    public CriticalityLevel getCriticality() { return criticality; }
    public Optional<RotationRecommendation> getRecommendation() { return Optional.ofNullable(recommendation); }
    public Optional<NaturalLanguageRedaction> getRedaction() { return Optional.ofNullable(redaction); }
    public ModelVersion getModelVersion() { return modelVersion; }
    public Long getLimitVersionId() { return limitVersionId; }
    public AssessmentStatus getStatus() { return status; }
    public CloseReason getCloseReason() { return closeReason; }
    public String getTriggerEventId() { return triggerEventId; }
    public Instant getAssessedAt() { return assessedAt; }
    public Instant getClosedAt() { return closedAt; }
}
