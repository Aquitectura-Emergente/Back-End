package com.emsafe.aiforecasting.infrastructure.persistence;

import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.ports.WorkerSnapshotRepository;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaWorkerSnapshotRepositoryAdapter implements WorkerSnapshotRepository {

    private final SnapshotJpaRepository jpa;

    public JpaWorkerSnapshotRepositoryAdapter(SnapshotJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public WorkerExposureSnapshot save(WorkerExposureSnapshot snapshot) {
        WorkerExposureSnapshotEntity entity = jpa.findById(snapshot.sessionId())
                .orElseGet(() -> new WorkerExposureSnapshotEntity(snapshot.sessionId()));
        entity.copyFrom(snapshot);
        return jpa.save(entity).toDomain();
    }

    @Override
    public Optional<WorkerExposureSnapshot> findBySessionId(String sessionId) {
        return jpa.findById(sessionId).map(WorkerExposureSnapshotEntity::toDomain);
    }

    @Override
    public List<WorkerExposureSnapshot> findByOrganizationIdAndZoneId(Long organizationId, String zoneId) {
        return jpa.findByOrganizationIdAndZoneId(organizationId, zoneId).stream()
                .map(WorkerExposureSnapshotEntity::toDomain)
                .toList();
    }
}
