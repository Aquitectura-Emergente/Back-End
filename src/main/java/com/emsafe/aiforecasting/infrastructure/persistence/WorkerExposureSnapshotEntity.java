package com.emsafe.aiforecasting.infrastructure.persistence;

import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "af_worker_exposure_snapshot")
@Getter
@NoArgsConstructor
public class WorkerExposureSnapshotEntity {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Version
    private Long version;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    @Column(name = "zone_id", length = 64)
    private String zoneId;

    @Column(name = "position_id")
    private Long positionId;

    @Column(name = "accumulated_index", nullable = false, precision = 38, scale = 6)
    private BigDecimal accumulatedIndex;

    @Column(name = "limit_index", nullable = false, precision = 38, scale = 6)
    private BigDecimal limitIndex;

    @Column(name = "session_started_at", nullable = false)
    private Instant sessionStartedAt;

    @Column(name = "planned_shift_end")
    private Instant plannedShiftEnd;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    WorkerExposureSnapshotEntity(String sessionId) {
        this.sessionId = sessionId;
    }

    void copyFrom(WorkerExposureSnapshot s) {
        this.organizationId = s.organizationId();
        this.workerId = s.workerId();
        this.zoneId = s.zoneId();
        this.positionId = s.positionId();
        this.accumulatedIndex = s.accumulatedIndex();
        this.limitIndex = s.limitIndex();
        this.sessionStartedAt = s.sessionStartedAt();
        this.plannedShiftEnd = s.plannedShiftEnd();
        this.capturedAt = s.capturedAt();
        this.updatedAt = s.updatedAt();
    }

    WorkerExposureSnapshot toDomain() {
        return new WorkerExposureSnapshot(organizationId, workerId, sessionId, zoneId, positionId,
                accumulatedIndex, limitIndex, sessionStartedAt, plannedShiftEnd, capturedAt, updatedAt);
    }
}
