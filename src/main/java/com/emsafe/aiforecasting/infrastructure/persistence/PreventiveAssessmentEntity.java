package com.emsafe.aiforecasting.infrastructure.persistence;

import com.emsafe.aiforecasting.domain.model.AssessmentStatus;
import com.emsafe.aiforecasting.domain.model.CloseReason;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ModelVersion;
import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persistencia del agregado. Los enums se guardan como VARCHAR (no como ENUM de MySQL)
 * para que añadir un valor no requiera migración.
 */
@Entity
@Table(name = "af_preventive_assessment")
@Getter
@NoArgsConstructor
public class PreventiveAssessmentEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Version
    private Long version;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** = sessionId mientras la evaluación está activa; NULL al cerrarse (restricción única). */
    @Column(name = "active_session_key", length = 64, unique = true)
    private String activeSessionKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AssessmentStatus status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private CriticalityLevel criticality;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "close_reason", length = 20)
    private CloseReason closeReason;

    // ─── ExposureProjection ──────────────────────────────────────────────────
    @Column(name = "current_index", nullable = false, precision = 38, scale = 6)
    private BigDecimal currentIndex;
    @Column(name = "limit_index", nullable = false, precision = 38, scale = 6)
    private BigDecimal limitIndex;
    @Column(name = "accumulation_rate", nullable = false, precision = 38, scale = 6)
    private BigDecimal accumulationRate;
    @Column(name = "projected_index", nullable = false, precision = 38, scale = 6)
    private BigDecimal projectedIndex;
    @Column(name = "remaining_margin", nullable = false, precision = 38, scale = 6)
    private BigDecimal remainingMargin;
    @Column(name = "consumed_ratio", nullable = false, precision = 38, scale = 6)
    private BigDecimal consumedRatio;
    @Column(name = "projected_ratio", nullable = false, precision = 38, scale = 6)
    private BigDecimal projectedRatio;
    @Column(name = "estimated_exceedance_at")
    private Instant estimatedExceedanceAt;
    @Column(name = "shift_end", nullable = false)
    private Instant shiftEnd;

    // ─── RotationRecommendation ──────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "rec_type", length = 20)
    private RecommendationType recType;
    @Column(name = "rec_source_worker_id")
    private Long recSourceWorkerId;
    @Column(name = "rec_target_worker_id")
    private Long recTargetWorkerId;
    @Column(name = "rec_source_position_id")
    private Long recSourcePositionId;
    @Column(name = "rec_target_position_id")
    private Long recTargetPositionId;
    @Column(name = "rec_benefit_index", precision = 38, scale = 6)
    private BigDecimal recBenefitIndex;
    @Column(name = "rec_reason", length = 500)
    private String recReason;

    // ─── NaturalLanguageRedaction ────────────────────────────────────────────
    @Column(name = "redaction_text", columnDefinition = "TEXT")
    private String redactionText;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "redaction_source", length = 20)
    private RedactionSource redactionSource;
    @Column(name = "redaction_generated_at")
    private Instant redactionGeneratedAt;

    // ─── Trazabilidad ────────────────────────────────────────────────────────
    @Column(name = "model_id", nullable = false, length = 50)
    private String modelId;
    @Column(name = "model_version", nullable = false, length = 20)
    private String modelVersion;
    @Column(name = "limit_version_id")
    private Long limitVersionId;
    @Column(name = "trigger_event_id", length = 100)
    private String triggerEventId;
    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;
    @Column(name = "closed_at")
    private Instant closedAt;

    PreventiveAssessmentEntity(String id) {
        this.id = id;
    }

    void copyFrom(PreventiveAssessment a) {
        organizationId = a.getOrganizationId();
        workerId = a.getWorkerId();
        sessionId = a.getSessionId();
        activeSessionKey = a.isActive() ? a.getSessionId() : null;
        status = a.getStatus();
        criticality = a.getCriticality();
        closeReason = a.getCloseReason();

        ExposureProjection p = a.getProjection();
        currentIndex = p.currentIndex();
        limitIndex = p.limitIndex();
        accumulationRate = p.accumulationRate();
        projectedIndex = p.projectedIndexAtShiftEnd();
        remainingMargin = p.remainingMargin();
        consumedRatio = p.consumedRatio();
        projectedRatio = p.projectedRatio();
        estimatedExceedanceAt = p.estimatedExceedanceAt();
        shiftEnd = p.shiftEnd();

        RotationRecommendation r = a.getRecommendation().orElse(null);
        recType = r == null ? null : r.type();
        recSourceWorkerId = r == null ? null : r.sourceWorkerId();
        recTargetWorkerId = r == null ? null : r.targetWorkerId();
        recSourcePositionId = r == null ? null : r.sourcePositionId();
        recTargetPositionId = r == null ? null : r.targetPositionId();
        recBenefitIndex = r == null ? null : r.estimatedBenefitIndex();
        recReason = r == null ? null : r.reason();

        NaturalLanguageRedaction n = a.getRedaction().orElse(null);
        redactionText = n == null ? null : n.text();
        redactionSource = n == null ? null : n.generatedBy();
        redactionGeneratedAt = n == null ? null : n.generatedAt();

        modelId = a.getModelVersion().modelId();
        modelVersion = a.getModelVersion().version();
        limitVersionId = a.getLimitVersionId();
        triggerEventId = a.getTriggerEventId();
        assessedAt = a.getAssessedAt();
        closedAt = a.getClosedAt();
    }

    PreventiveAssessment toDomain() {
        ExposureProjection projection = new ExposureProjection(currentIndex, limitIndex, accumulationRate,
                projectedIndex, remainingMargin, consumedRatio, projectedRatio, estimatedExceedanceAt, shiftEnd);
        RotationRecommendation recommendation = recType == null ? null
                : new RotationRecommendation(recType, recSourceWorkerId, recTargetWorkerId, recSourcePositionId,
                recTargetPositionId, recBenefitIndex, recReason);
        NaturalLanguageRedaction redaction = redactionText == null ? null
                : new NaturalLanguageRedaction(redactionText, redactionSource, redactionGeneratedAt);
        return PreventiveAssessment.restore(id, organizationId, workerId, sessionId, projection, criticality,
                recommendation, redaction, new ModelVersion(modelId, modelVersion), limitVersionId, status,
                closeReason, triggerEventId, assessedAt, closedAt);
    }
}
