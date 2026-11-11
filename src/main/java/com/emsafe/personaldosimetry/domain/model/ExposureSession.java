package com.emsafe.personaldosimetry.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "personal_exposure_sessions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_pd_active_worker", columnNames = "active_worker_id"),
        @UniqueConstraint(name = "uk_pd_active_device", columnNames = "active_device_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExposureSession {
    public enum State { OPEN, CLOSING, CLOSED }
    public record Crossings(boolean warning, boolean exceeded) {}

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;
    @Version
    private Long version;
    @Column(nullable = false)
    private Long organizationId;
    @Column(nullable = false)
    private Long workerId;
    @Column(nullable = false)
    private Long deviceId;
    @Column(nullable = false)
    private Long jobPositionId;
    private Long activeWorkerId;
    private Long activeDeviceId;
    @Column(nullable = false)
    private Long limitVersionId;
    @Column(nullable = false, precision = 38, scale = 12)
    private BigDecimal limitIndex;
    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal warningRatio;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;
    @Column(nullable = false)
    private Instant startedAt;
    private Instant endedAt;
    private Instant reconciliationDeadline;
    private Instant finalizedAt;
    @Column(nullable = false)
    private Instant latestCaptureTo;
    @Column(nullable = false, precision = 38, scale = 12)
    private BigDecimal accumulatedIndex;
    @Column(nullable = false)
    private long coveredMicros;
    @Column(nullable = false)
    private boolean warningRaised;
    @Column(nullable = false)
    private boolean limitExceeded;

    public static ExposureSession open(Long organizationId, Long workerId, Long deviceId,
                                       OccupationalLimit limit, Instant startedAt) {
        ExposureIndex.positiveId(workerId, "workerId");
        ExposureIndex.positiveId(deviceId, "deviceId");
        ExposureIndex.timestamp(startedAt);
        if (limit == null || limit.getId() == null || !organizationId.equals(limit.getOrganizationId())
                || limit.getEffectiveFrom().isAfter(startedAt)) {
            throw DosimetryRuleException.invalid("A persisted, effective limit for this organization is required");
        }
        ExposureSession s = new ExposureSession();
        s.organizationId = organizationId;
        s.workerId = workerId;
        s.deviceId = deviceId;
        s.jobPositionId = limit.getJobPositionId();
        s.activeWorkerId = workerId;
        s.activeDeviceId = deviceId;
        s.limitVersionId = limit.getId();
        s.limitIndex = limit.getLimitIndex();
        s.warningRatio = limit.getWarningRatio();
        s.state = State.OPEN;
        s.startedAt = startedAt;
        s.latestCaptureTo = startedAt;
        s.accumulatedIndex = BigDecimal.ZERO;
        return s;
    }

    public void validateCapture(Instant from, Instant to, Instant now) {
        ExposureIndex.durationMicros(from, to);
        if (from.isBefore(startedAt) || to.isAfter(now)
                || (endedAt != null && to.isAfter(endedAt))) {
            throw DosimetryRuleException.invalid("Capture interval is outside the session or in the future");
        }
    }

    public Crossings apply(BigDecimal contribution, long micros, Instant capturedTo) {
        if (state == State.CLOSED) throw DosimetryRuleException.conflict("Closed sessions are immutable");
        ExposureIndex.decimal(contribution, 24, 12, "contribution");
        if (micros <= 0) throw DosimetryRuleException.invalid("Coverage must be positive");
        BigDecimal updated = accumulatedIndex.add(contribution);
        ExposureIndex.decimal(updated, 24, 12, "accumulatedIndex");
        long updatedCoverage;
        try {
            updatedCoverage = Math.addExact(coveredMicros, micros);
        } catch (ArithmeticException ex) {
            throw DosimetryRuleException.invalid("Coverage is outside the supported range");
        }
        boolean warning = !warningRaised && updated.compareTo(limitIndex.multiply(warningRatio)) > 0;
        boolean exceeded = !limitExceeded && updated.compareTo(limitIndex) > 0;
        accumulatedIndex = updated;
        coveredMicros = updatedCoverage;
        if (capturedTo.isAfter(latestCaptureTo)) latestCaptureTo = capturedTo;
        warningRaised |= warning;
        limitExceeded |= exceeded;
        return new Crossings(warning, exceeded);
    }

    public void requestClose(Instant endedAt, Instant now) {
        ExposureIndex.timestamp(endedAt);
        if (state != State.OPEN) throw DosimetryRuleException.conflict("Session is not open");
        if (!endedAt.isAfter(startedAt) || endedAt.isBefore(latestCaptureTo) || endedAt.isAfter(now)) {
            throw DosimetryRuleException.invalid("End time must contain all accepted captures and cannot be in the future");
        }
        this.endedAt = endedAt;
        reconciliationDeadline = endedAt.plus(Duration.ofMinutes(30));
        state = State.CLOSING;
        activeWorkerId = null;
        activeDeviceId = null;
    }

    public void finalizeSession(Instant now, boolean deliveryConfirmed) {
        if (state != State.CLOSING) throw DosimetryRuleException.conflict("Session is not awaiting reconciliation");
        if (!deliveryConfirmed && now.isBefore(reconciliationDeadline)) {
            throw DosimetryRuleException.conflict("Pending readings must be confirmed or the reconciliation deadline must pass");
        }
        finalizedAt = now;
        state = State.CLOSED;
    }

    public BigDecimal percentage() {
        return ExposureIndex.percentage(accumulatedIndex, limitIndex);
    }

    public BigDecimal coverage(Instant now) {
        Instant end = endedAt == null ? now : endedAt;
        if (!end.isAfter(startedAt)) return BigDecimal.ZERO.setScale(2);
        long span = ExposureIndex.durationMicros(startedAt, end);
        return ExposureIndex.percentage(BigDecimal.valueOf(coveredMicros), BigDecimal.valueOf(span));
    }

    public boolean coverageComplete() {
        return state == State.CLOSED && endedAt != null
                && coveredMicros == ExposureIndex.durationMicros(startedAt, endedAt);
    }
}
