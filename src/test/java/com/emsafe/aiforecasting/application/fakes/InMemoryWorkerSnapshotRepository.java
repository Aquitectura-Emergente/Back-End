package com.emsafe.aiforecasting.application.fakes;

import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.ports.WorkerSnapshotRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class InMemoryWorkerSnapshotRepository implements WorkerSnapshotRepository {

    private final Map<String, WorkerExposureSnapshot> store = new LinkedHashMap<>();

    @Override
    public WorkerExposureSnapshot save(WorkerExposureSnapshot snapshot) {
        store.put(snapshot.sessionId(), snapshot);
        return snapshot;
    }

    @Override
    public Optional<WorkerExposureSnapshot> findBySessionId(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public List<WorkerExposureSnapshot> findByOrganizationIdAndZoneId(Long organizationId, String zoneId) {
        return store.values().stream()
                .filter(s -> s.organizationId().equals(organizationId))
                .filter(s -> Objects.equals(s.zoneId(), zoneId))
                .toList();
    }

    public int size() {
        return store.size();
    }
}
