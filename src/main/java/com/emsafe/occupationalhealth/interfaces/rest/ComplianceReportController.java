package com.emsafe.occupationalhealth.interfaces.rest;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.occupationalhealth.application.GenerateComplianceReportCommandHandler;
import com.emsafe.occupationalhealth.application.IdempotencyGuard;
import com.emsafe.occupationalhealth.application.MitigationAccessPolicy;
import com.emsafe.occupationalhealth.domain.model.ComplianceReport;
import com.emsafe.occupationalhealth.domain.model.ReportPeriod;
import com.emsafe.occupationalhealth.domain.model.ReportScope;
import com.emsafe.occupationalhealth.domain.services.ReportRenderer;
import com.emsafe.occupationalhealth.infrastructure.persistence.ComplianceReportRepository;
import com.emsafe.occupationalhealth.interfaces.rest.dto.ComplianceReportResource;
import com.emsafe.occupationalhealth.interfaces.rest.dto.GenerateComplianceReportRequest;
import com.emsafe.shared.dto.ApiResponse;
import com.emsafe.shared.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * REST de los reportes de cumplimiento (informe §5.6.2).
 *
 * Generar y exportar son del SSO. Cada exportación queda registrada en la
 * auditoría: el informe lo exige porque el reporte contiene exposición, que es
 * dato de salud (Ley 29733).
 */
@RestController
@RequestMapping("/api/v1/occupational-health/compliance-reports")
@RequiredArgsConstructor
public class ComplianceReportController {

    private final GenerateComplianceReportCommandHandler generateHandler;
    private final ComplianceReportRepository reportRepository;
    private final ReportRenderer renderer;
    private final IdempotencyGuard idempotency;
    private final MitigationAccessPolicy accessPolicy;

    @PostMapping
    public ResponseEntity<ApiResponse<ComplianceReportResource>> generate(
            @Valid @RequestBody GenerateComplianceReportRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication auth) {
        AppUser sso = accessPolicy.assertCanDecide(auth);
        Long organizationId = accessPolicy.organizationOf(sso);

        // Sin la clave, repetir la petición crea una versión nueva — que es el
        // comportamiento correcto de la regla 7. Con la clave, un doble toque
        // devuelve la versión que ya se generó.
        Long reportId = idempotency.runOnce(organizationId, idempotencyKey,
                "POST /compliance-reports",
                () -> generateHandler.handle(organizationId, ReportScope.from(req.scope()),
                        req.workerId(), ReportPeriod.of(req.from(), req.to()),
                        sso.getId()).getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                "Compliance report generated",
                ComplianceReportResource.from(load(reportId, organizationId))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ComplianceReportResource>> getById(
            @PathVariable Long id, Authentication auth) {
        AppUser user = accessPolicy.assertCanRead(auth);
        ComplianceReport report = load(id, accessPolicy.organizationOf(user));
        return ResponseEntity.ok(ApiResponse.ok(ComplianceReportResource.from(report)));
    }

    /**
     * Descarga el archivo.
     *
     * No se devuelve un enlace temporal como en el informe —que apunta a Azure
     * Blob— sino el archivo por la propia API: así cada descarga pasa por
     * autorización y queda auditada, y no existe una URL que pueda compartirse.
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable Long id,
            @RequestParam(defaultValue = "pdf") String format,
            Authentication auth) {
        AppUser sso = accessPolicy.assertCanDecide(auth);
        ComplianceReport report = load(id, accessPolicy.organizationOf(sso));

        if (!renderer.fileExtension().equalsIgnoreCase(format)) {
            throw new com.emsafe.occupationalhealth.domain.model.MitigationUnprocessableException(
                    "Unsupported export format: " + format);
        }

        byte[] content = generateHandler.export(report, sso.getId());
        String filename = "compliance-report-" + report.getId()
                + "-v" + report.getVersion() + "." + renderer.fileExtension();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(renderer.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(content);
    }

    private ComplianceReport load(Long id, Long organizationId) {
        return reportRepository.findByIdAndOrganization(id, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("ComplianceReport", id));
    }
}
