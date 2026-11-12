package com.emsafe.aiforecasting.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data de {@code af_worker_exposure_snapshot}. Solo lo usa {@link JpaWorkerSnapshotRepositoryAdapter}. */
public interface SnapshotJpaRepository extends JpaRepository<WorkerExposureSnapshotEntity, String> {

    List<WorkerExposureSnapshotEntity> findByOrganizationIdAndZoneId(Long organizationId, String zoneId);
}
