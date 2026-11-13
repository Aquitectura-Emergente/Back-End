package com.emsafe.aiforecasting.domain.ports;

import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;

import java.util.List;
import java.util.Optional;

/** Modelo de lectura local de sesiones abiertas (una fila por sesión). */
public interface WorkerSnapshotRepository {

    /** Alta o actualización por {@code sessionId}. */
    WorkerExposureSnapshot save(WorkerExposureSnapshot snapshot);

    Optional<WorkerExposureSnapshot> findBySessionId(String sessionId);

    /** Sesiones abiertas de la misma organización y zona: el pool de candidatos de rotación. */
    List<WorkerExposureSnapshot> findByOrganizationIdAndZoneId(Long organizationId, String zoneId);
}
