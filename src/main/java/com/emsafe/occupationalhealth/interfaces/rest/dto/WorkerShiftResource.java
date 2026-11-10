package com.emsafe.occupationalhealth.interfaces.rest.dto;

import com.emsafe.occupationalhealth.domain.model.PositionAssignment;
import com.emsafe.occupationalhealth.domain.model.WorkerShift;

import java.util.List;

/** El turno del operario y su secuencia de puestos (informe §5.6.2). */
public record WorkerShiftResource(
        Long id,
        Long workerId,
        String shiftDate,
        String state,
        String exposureSessionId,
        Long currentJobPositionId,
        Long currentPlantZoneId,
        List<PositionAssignmentResource> assignments
) {
    public static WorkerShiftResource from(WorkerShift s) {
        PositionAssignment current = s.currentAssignment().orElse(null);
        return new WorkerShiftResource(
                s.getId(),
                s.getWorkerId(),
                s.getShiftDate() != null ? s.getShiftDate().toString() : null,
                s.getState().name(),
                s.getExposureSessionId(),
                current != null ? current.getJobPositionId() : null,
                current != null ? current.getPlantZoneId() : null,
                s.getAssignments().stream().map(PositionAssignmentResource::from).toList());
    }

    public record PositionAssignmentResource(
            Long id,
            Long jobPositionId,
            Long plantZoneId,
            String from,
            String to,
            Long sourceActionId,
            long durationMinutes
    ) {
        static PositionAssignmentResource from(PositionAssignment a) {
            return new PositionAssignmentResource(
                    a.getId(),
                    a.getJobPositionId(),
                    a.getPlantZoneId(),
                    a.getInterval().getFrom().toString(),
                    a.getInterval().getTo() != null ? a.getInterval().getTo().toString() : null,
                    a.getSourceActionId(),
                    a.getInterval().durationMinutes());
        }
    }
}
