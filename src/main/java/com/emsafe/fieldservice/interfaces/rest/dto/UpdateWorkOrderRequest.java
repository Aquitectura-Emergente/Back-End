package com.emsafe.fieldservice.interfaces.rest.dto;

import com.emsafe.fieldservice.domain.model.WorkOrderType;

import java.time.LocalDate;

/**
 * Admin update for an existing work order (Edit Work Order form).
 * Only non-null fields are applied.
 */
public record UpdateWorkOrderRequest(
        WorkOrderType type,
        String client,
        Long clientId,
        String location,
        String city,
        LocalDate scheduledDate,
        String scheduledTime,
        Long technicianId,
        String priority,
        String notes
) {}
