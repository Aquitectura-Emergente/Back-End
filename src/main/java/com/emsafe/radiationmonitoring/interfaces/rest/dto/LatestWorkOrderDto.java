package com.emsafe.radiationmonitoring.interfaces.rest.dto;

import com.emsafe.fieldservice.domain.model.WorkOrder;

import java.time.LocalDate;

public record LatestWorkOrderDto(
        Long id,
        String orderId,
        String siteLocation,
        String technician,
        String status,
        LocalDate date
) {
    public static LatestWorkOrderDto from(WorkOrder wo) {
        String site = wo.getLocation() != null && wo.getCity() != null
                ? wo.getLocation() + " — " + wo.getCity()
                : (wo.getLocation() != null ? wo.getLocation() : wo.getCity());

        return new LatestWorkOrderDto(
                wo.getId(),
                wo.getOrderId(),
                site,
                wo.getTechnicianName(),
                wo.getStatus().name().toLowerCase().replace("_", "-"),
                wo.getScheduledDate()
        );
    }
}
