package com.emsafe.fieldservice.interfaces.rest.dto;

import com.emsafe.iottelemetry.interfaces.rest.dto.DeviceDto;
import com.emsafe.fieldservice.domain.model.WorkOrder;
import com.emsafe.fieldservice.domain.model.WorkOrderEvidence;

import java.time.LocalDate;
import java.util.List;

/**
 * Full detail DTO used in technician portal.
 */
public record WorkOrderDetailDto(
        Long id,
        String orderId,
        String type,
        String status,
        String client,
        Long clientId,
        String location,
        LocalDate scheduledDate,
        String scheduledTime,
        Long technicianId,
        String priority,
        String serviceType,
        String contactName,
        String contactRole,
        String contactPhone,
        String contactEmail,
        String accessInstructions,
        Integer expectedSensors,
        String assetId,
        String technicianNotes,
        String completedAt,
        String cancellationReason,
        List<String> requiredTools,
        List<SensorDto> sensors,
        List<ActivityLogDto> activityLog,
        List<String> evidence,
        List<MaintenanceActionDto> maintenanceActions,
        List<DeviceDto> clientDevices
) {
    private static String typeDisplay(WorkOrder wo) {
        String n = wo.getType().name();
        return n.charAt(0) + n.substring(1).toLowerCase();
    }

    /** List view — no client devices resolved. */
    public static WorkOrderDetailDto from(WorkOrder wo) {
        return from(wo, List.of());
    }

    /** Detail view — clientDevices resolved by the service. */
    public static WorkOrderDetailDto from(WorkOrder wo, List<DeviceDto> clientDevices) {
        return new WorkOrderDetailDto(
                wo.getId(),
                wo.getOrderId(),
                typeDisplay(wo),
                wo.getStatus().name().toLowerCase().replace("_", "-"),
                wo.getClient(),
                wo.getClientUser() != null ? wo.getClientUser().getId() : null,
                wo.getLocation(),
                wo.getScheduledDate(),
                wo.getScheduledTime(),
                wo.getTechnician() != null ? wo.getTechnician().getId() : null,
                wo.getPriority(),
                typeDisplay(wo),
                wo.getContactName(),
                wo.getContactRole(),
                wo.getContactPhone(),
                wo.getContactEmail(),
                wo.getAccessInstructions(),
                wo.getExpectedSensors(),
                wo.getAssetId(),
                wo.getTechnicianNotes(),
                wo.getCompletedAt() != null ? wo.getCompletedAt().toString() : null,
                wo.getCancellationReason(),
                wo.getRequiredTools(),
                wo.getSensors().stream().map(SensorDto::from).toList(),
                wo.getActivityLog().stream().map(ActivityLogDto::from).toList(),
                wo.getEvidence().stream().map(WorkOrderEvidence::getImage).toList(),
                wo.getMaintenanceActions().stream().map(MaintenanceActionDto::from).toList(),
                clientDevices
        );
    }
}
