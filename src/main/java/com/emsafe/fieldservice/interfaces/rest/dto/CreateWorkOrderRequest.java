package com.emsafe.fieldservice.interfaces.rest.dto;

import com.emsafe.fieldservice.domain.model.WorkOrderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record CreateWorkOrderRequest(
        @NotNull(message = "Type is required")
        WorkOrderType type,

        @NotBlank(message = "Client is required")
        String client,

        // Optional reference to the client user — enables resolving the client's
        // devices in Maintenance / Collection flows.
        Long clientId,

        String location,
        String city,
        LocalDate scheduledDate,
        String scheduledTime,

        Long technicianId,

        String priority,
        String contactName,
        String contactRole,
        String contactPhone,
        String contactEmail,
        String accessInstructions,
        Integer expectedSensors,
        String assetId,
        // Admin notes — persisted on the work order and shown to the technician.
        String notes,
        List<String> requiredTools
) {}
