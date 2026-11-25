package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.application.AnalyticsSummaryQueryHandler;
import com.emsafe.aiforecasting.application.ForecastingAccessPolicy;
import com.emsafe.aiforecasting.interfaces.rest.dto.AnalyticsResources.Summary;
import com.emsafe.shared.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/ai-forecasting/analytics")
@Tag(name = "AI Forecasting - Analítica",
        description = "Resumen del histórico de jornadas y de las proyecciones. ADMIN, o la organización dueña "
                + "(cuenta CLIENT company o su SSO_RESPONSIBLE). WORKER y TECHNICIAN: 403.")
public class AiForecastingAnalyticsController {

    private final AnalyticsSummaryQueryHandler summaries;
    private final ForecastingAccessPolicy access;

    public AiForecastingAnalyticsController(AnalyticsSummaryQueryHandler summaries, ForecastingAccessPolicy access) {
        this.summaries = summaries;
        this.access = access;
    }

    @GetMapping("/organizations/{organizationId}/summary")
    @Operation(summary = "Resumen analítico de una organización",
            description = "Jornadas cerradas en Personal Dosimetry cuyo fin cae en [from, to] (días en `zone`): "
                    + "exposición por operario, tendencia por semana ISO, puestos, operarios sobre la advertencia "
                    + "y porcentaje de jornadas cerca del límite. Además, los operarios críticos proyectados ahora "
                    + "(solo jornadas abiertas). Sin `to`, hasta hoy; sin `from`, 28 días. Máximo 366 días. "
                    + "403 fuera de la organización (antes de validar el periodo y la zona); 400 con fechas mal "
                    + "formadas, periodo inválido o zona inválida.")
    public ApiResponse<Summary> summary(
            Authentication auth,
            @PathVariable Long organizationId,
            @Parameter(description = "Primer día (inclusivo)", example = "2026-11-02")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Último día (inclusivo)", example = "2026-11-22")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Zona IANA para interpretar from/to y agrupar por semana",
                    example = AnalyticsSummaryQueryHandler.DEFAULT_ZONE)
            @RequestParam(required = false) String zone) {
        access.requireOrganizationAccess(access.actor(auth), organizationId);
        return ApiResponse.ok(Summary.from(summaries.summarize(organizationId, from, to, zone)));
    }
}
