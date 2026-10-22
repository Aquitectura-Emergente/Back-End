package com.emsafe.fieldservice.interfaces.rest.dto;

import com.emsafe.fieldservice.domain.model.WorkOrder;

import java.time.LocalDate;

/**
 * Admin work-order detail used to pre-fill the Edit Work Order form.
 */
public record WorkOrderEditDto(
        Long id,
        String orderId,
        String type,
        String status,
        String client,
        Long clientId,
        String location,
        String city,
        LocalDate date,
        LocalDate scheduledDate,
        String scheduledTime,
        Long technicianId,
        String technician,
        String technicianInitials,
        String priority,
        String notes,
        String cancellationReason
) {
    public static WorkOrderEditDto from(WorkOrder wo) {
        String typeName = wo.getType().name();
        String typeDisplay = typeName.charAt(0) + typeName.substring(1).toLowerCase();
        return new WorkOrderEditDto(
                wo.getId(),
                wo.getOrderId(),
                typeDisplay,
                wo.getStatus().name().toLowerCase().replace("_", "-"),
                wo.getClient(),
                wo.getClientUser() != null ? wo.getClientUser().getId() : null,
                wo.getLocation(),
                wo.getCity(),
                wo.getScheduledDate(),
                wo.getScheduledDate(),
                wo.getScheduledTime(),
                wo.getTechnician() != null ? wo.getTechnician().getId() : null,
                wo.getTechnicianName(),
                wo.getTechnicianInitials(),
                wo.getPriority(),
                wo.getTechnicianNotes(),
                wo.getCancellationReason()
        );
    }
}
