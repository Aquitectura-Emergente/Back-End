package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.application.AnalyticsSummaryQueryHandler;
import com.emsafe.aiforecasting.application.ForecastingAccessPolicy;
import com.emsafe.aiforecasting.application.PeriodInsightsQueryHandler;
import com.emsafe.aiforecasting.interfaces.rest.dto.InsightsResources.Insights;
import com.emsafe.aiforecasting.interfaces.rest.dto.AnalyticsResources.Summary;
import com.emsafe.shared.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final PeriodInsightsQueryHandler insights;
    private final ForecastingAccessPolicy access;

    public AiForecastingAnalyticsController(AnalyticsSummaryQueryHandler summaries, PeriodInsightsQueryHandler insights,
                                            ForecastingAccessPolicy access) {
        this.summaries = summaries;
        this.insights = insights;
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

    @PostMapping("/organizations/{organizationId}/insights")
    @Operation(summary = "Análisis con IA del periodo (consultivo)",
            description = "Lectura del resumen del periodo para el SSO: resumen, hallazgos (INFO, ATENCION, CRITICO), "
                    + "recomendaciones preventivas (ALTA, MEDIA, BAJA) y calidad de los datos. A Gemini solo se envían "
                    + "datos agregados con seudónimos (\"Operario A\"); ningún nombre, email ni ID real sale del "
                    + "backend. Con el LLM apagado, sin clave, ante timeout, error o respuesta inválida, el análisis "
                    + "lo generan reglas deterministas (generatedBy = FALLBACK). Es de solo lectura: no cambia "
                    + "criticidades, evaluaciones ni eventos. Caché de 10 min por organización, periodo y zona; "
                    + "`refresh=true` la renueva. Mismo acceso y parámetros que `summary`: 403 fuera de la "
                    + "organización, 400 con periodo o zona inválidos.")
    public ApiResponse<Insights> insights(
            Authentication auth,
            @PathVariable Long organizationId,
            @Parameter(description = "Primer día (inclusivo)", example = "2026-11-02")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Último día (inclusivo)", example = "2026-11-22")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Zona IANA para interpretar from/to",
                    example = AnalyticsSummaryQueryHandler.DEFAULT_ZONE)
            @RequestParam(required = false) String zone,
            @Parameter(description = "true: ignora la caché de 10 min y genera un análisis nuevo")
            @RequestParam(defaultValue = "false") boolean refresh) {
        access.requireOrganizationAccess(access.actor(auth), organizationId);
        return ApiResponse.ok(Insights.from(insights.analyze(organizationId, from, to, zone, refresh)));
    }
}
