package com.emsafe.occupationalhealth.interfaces.rest.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Petición de un reporte de cumplimiento (POST /compliance-reports).
 *
 * `scope` admite WORKER o PLANT_ZONE. `workerId` es obligatorio en el primero y
 * tiene que faltar en el segundo: un reporte por zona que señale a una persona
 * incumpliría RF32 y RNF11, y el handler lo rechaza con 422.
 */
public record GenerateComplianceReportRequest(
        @NotNull(message = "scope is required (WORKER or PLANT_ZONE)") String scope,
        Long workerId,
        @NotNull(message = "from is required") LocalDate from,
        @NotNull(message = "to is required") LocalDate to
) {
}
