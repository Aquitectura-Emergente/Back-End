package com.emsafe.fieldservice.interfaces.rest.dto;

import com.emsafe.fieldservice.domain.model.WorkOrder;

import java.time.LocalDate;

/**
 * Lightweight DTO used in admin work-order list.
 */
public record WorkOrderDto(
        Long id,
        String orderId,
        String type,
        String status,
        String client,
        String location,
        String city,
        LocalDate date,
        String technician,
        String technicianInitials
) {
    public static WorkOrderDto from(WorkOrder wo) {
        return new WorkOrderDto(
                wo.getId(),
                wo.getOrderId(),
                wo.getType().name().charAt(0) + wo.getType().name().substring(1).toLowerCase(),
                wo.getStatus().name().toLowerCase().replace("_", "-"),
                wo.getClient(),
                wo.getLocation(),
                wo.getCity(),
                wo.getScheduledDate(),
                wo.getTechnicianName(),
                wo.getTechnicianInitials()
        );
    }
}
