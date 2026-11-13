package com.emsafe.personaldosimetry.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "personal_dosimetry_limits", uniqueConstraints =
        @UniqueConstraint(name = "uk_pd_limit_version",
                columnNames = {"organization_id", "job_position_id", "version_number"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OccupationalLimit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long organizationId;
    @Column(nullable = false)
    private Long jobPositionId;
    @Column(nullable = false)
    private int versionNumber;
    @Column(nullable = false, precision = 38, scale = 12)
    private BigDecimal limitIndex;
    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal warningRatio;
    @Column(nullable = false)
    private Instant effectiveFrom;
    @Column(name = "criterion_reference", nullable = false, length = 250)
    private String reference;
    @Column(nullable = false)
    private Long changedBy;
    @Column(nullable = false)
    private Instant createdAt;

    public OccupationalLimit(Long organizationId, Long jobPositionId, int versionNumber,
                             BigDecimal limitIndex, BigDecimal warningRatio, Instant effectiveFrom,
                             String reference, Long changedBy, Instant createdAt) {
        ExposureIndex.positiveId(organizationId, "organizationId");
        ExposureIndex.positiveId(jobPositionId, "jobPositionId");
        ExposureIndex.decimal(limitIndex, 24, 12, "limitIndex");
        ExposureIndex.decimal(warningRatio, 1, 6, "warningRatio");
        if (limitIndex.signum() == 0 || warningRatio.signum() == 0
                || warningRatio.compareTo(BigDecimal.ONE) >= 0) {
            throw DosimetryRuleException.invalid("Limit must be positive and warningRatio must be between 0 and 1");
        }
        if (reference == null || reference.isBlank() || reference.length() > 250) {
            throw DosimetryRuleException.invalid("A criterion reference of at most 250 characters is required");
        }
        ExposureIndex.timestamp(effectiveFrom);
        this.organizationId = organizationId;
        this.jobPositionId = jobPositionId;
        this.versionNumber = versionNumber;
        this.limitIndex = limitIndex;
        this.warningRatio = warningRatio;
        this.effectiveFrom = effectiveFrom;
        this.reference = reference.trim();
        this.changedBy = changedBy;
        this.createdAt = createdAt;
    }
}
