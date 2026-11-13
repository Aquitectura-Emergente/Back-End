package com.emsafe.personaldosimetry.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "personal_exposure_readings", uniqueConstraints =
        @UniqueConstraint(name = "uk_pd_reading_identity",
                columnNames = {"organization_id", "device_id", "boot_id", "sequence_number"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonalReading {
    public enum Status { ACCEPTED, LATE }
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 36)
    private String id;
    @Column(nullable = false, length = 36)
    private String sessionId;
    @Column(nullable = false)
    private Long organizationId;
    @Column(nullable = false)
    private Long deviceId;
    @Column(nullable = false, length = 64)
    private String bootId;
    @Column(name = "sequence_number", nullable = false)
    private long sequence;
    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal fieldMicrotesla;
    @Column(nullable = false)
    private Instant capturedFrom;
    @Column(nullable = false)
    private Instant capturedTo;
    @Column(nullable = false)
    private Instant receivedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;
    /** El sensor estaba saturado: el campo fue de al menos {@code fieldMicrotesla}. */
    @Column(nullable = false)
    private boolean saturated;

    public PersonalReading(ExposureSession session, Long deviceId, String bootId, long sequence,
                           BigDecimal fieldMicrotesla, Instant from, Instant to, Instant receivedAt) {
        if (!session.getDeviceId().equals(deviceId)) {
            throw DosimetryRuleException.invalid("Reading device does not match the assigned dosimeter");
        }
        if (bootId == null || bootId.isBlank() || bootId.trim().length() > 64 || sequence < 0) {
            throw DosimetryRuleException.invalid("A bootId and a non-negative sequence are required");
        }
        ExposureIndex.contribution(fieldMicrotesla, from, to);
        session.validateCapture(from, to, receivedAt);
        this.sessionId = session.getId();
        this.organizationId = session.getOrganizationId();
        this.deviceId = deviceId;
        this.bootId = bootId.trim();
        this.sequence = sequence;
        this.fieldMicrotesla = fieldMicrotesla;
        this.capturedFrom = from;
        this.capturedTo = to;
        this.receivedAt = receivedAt;
        this.status = session.getState() == ExposureSession.State.CLOSED ? Status.LATE : Status.ACCEPTED;
    }

    public void markSaturated() {
        this.saturated = true;
    }

    public boolean sameCapture(PersonalReading other) {
        return sessionId.equals(other.sessionId) && fieldMicrotesla.compareTo(other.fieldMicrotesla) == 0
                && capturedFrom.equals(other.capturedFrom) && capturedTo.equals(other.capturedTo);
    }
}
