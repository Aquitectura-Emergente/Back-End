package com.emsafe.occupationalhealth.interfaces.rest.dto;

import com.emsafe.occupationalhealth.domain.model.AffectedWorker;

public record AffectedWorkerResource(
        Long workerId,
        Long fromPositionId,
        Long toPositionId,
        Long toZoneId,
        DoseSnapshotResource doseAtDecision
) {
    public static AffectedWorkerResource from(AffectedWorker w) {
        return new AffectedWorkerResource(
                w.getWorkerId(),
                w.getFromPositionId(),
                w.getToPositionId(),
                w.getToZoneId(),
                DoseSnapshotResource.from(w.getDoseAtDecision()));
    }
}
