package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.application.AssessmentQueryHandler;
import com.emsafe.aiforecasting.application.CriticalWorkersQueryHandler;
import com.emsafe.aiforecasting.application.ForecastingAccessPolicy;
import com.emsafe.aiforecasting.application.ForecastingActor;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.AssessmentSearchCriteria;
import com.emsafe.aiforecasting.interfaces.rest.dto.ForecastingResources.Assessment;
import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.shared.dto.PageResponse;
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

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/ai-forecasting")
@Tag(name = "AI Forecasting - Consultas",
        description = "Evaluaciones y proyecciones. ADMIN, o la organización dueña sobre sus propios datos.")
public class AiForecastingQueryController {

    private final AssessmentQueryHandler assessments;
    private final CriticalWorkersQueryHandler criticalWorkers;
    private final ForecastingAccessPolicy access;

    public AiForecastingQueryController(AssessmentQueryHandler assessments,
                                        CriticalWorkersQueryHandler criticalWorkers,
                                        ForecastingAccessPolicy access) {
        this.assessments = assessments;
        this.criticalWorkers = criticalWorkers;
        this.access = access;
    }

    @GetMapping("/assessments/{id}")
    @Operation(summary = "Detalle de una evaluación", description = "404 si no existe; 403 si es de otra organización.")
    public ApiResponse<Assessment> getAssessment(Authentication auth, @PathVariable String id) {
        ForecastingActor actor = access.actor(auth);
        PreventiveAssessment assessment = assessments.getById(id);
        access.requireOrganizationAccess(actor, assessment.getOrganizationId());
        return ApiResponse.ok(ForecastingAssembler.toResource(assessment));
    }

    @GetMapping("/assessments")
    @Operation(summary = "Búsqueda paginada de evaluaciones",
            description = "Filtros opcionales; from/to aplican a assessedAt (ISO-8601 UTC). Orden: más recientes "
                    + "primero. Una organización dueña solo ve las suyas.")
    public ApiResponse<PageResponse<Assessment>> searchAssessments(
            Authentication auth,
            @RequestParam(required = false) Long organizationId,
            @RequestParam(required = false) Long workerId,
            @RequestParam(required = false) String sessionId,
            @Parameter(example = "2026-11-10T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(example = "2026-11-10T23:59:59Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Desde 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Entre 1 y 200") @RequestParam(defaultValue = "20") int size) {
        Long scope = access.organizationScope(access.actor(auth), organizationId);
        return ApiResponse.ok(ForecastingAssembler.toPage(
                assessments.search(new AssessmentSearchCriteria(scope, workerId, sessionId, from, to), page, size),
                ForecastingAssembler::toResource));
    }

    @GetMapping("/projections/workers/{workerId}")
    @Operation(summary = "Evaluación activa del operario",
            description = "404 si no tiene evaluación activa; 403 si pertenece a otra organización.")
    public ApiResponse<Assessment> activeForWorker(Authentication auth, @PathVariable Long workerId) {
        ForecastingActor actor = access.actor(auth);
        PreventiveAssessment assessment = assessments.activeForWorker(workerId);
        access.requireOrganizationAccess(actor, assessment.getOrganizationId());
        return ApiResponse.ok(ForecastingAssembler.toResource(assessment));
    }

    @GetMapping("/projections/critical-workers")
    @Operation(summary = "Operarios priorizados por urgencia",
            description = "Evaluaciones activas ordenadas por criticidad descendente y luego por excedencia "
                    + "estimada ascendente (sin excedencia al final). organizationId es obligatorio para ADMIN; "
                    + "una organización dueña usa la suya si lo omite.")
    public ApiResponse<PageResponse<Assessment>> criticalWorkers(
            Authentication auth,
            @RequestParam(required = false) Long organizationId,
            @Parameter(description = "Criticidad mínima (inclusiva). Sin valor: sin filtro.")
            @RequestParam(required = false) CriticalityLevel minCriticality,
            @Parameter(description = "Desde 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Entre 1 y 200") @RequestParam(defaultValue = "20") int size) {
        Long scope = access.organizationScope(access.actor(auth), organizationId);
        return ApiResponse.ok(ForecastingAssembler.toPage(
                criticalWorkers.list(scope, minCriticality, page, size), ForecastingAssembler::toResource));
    }
}
