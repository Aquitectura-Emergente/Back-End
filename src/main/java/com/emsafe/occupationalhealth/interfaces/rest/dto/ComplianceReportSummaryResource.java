package com.emsafe.occupationalhealth.interfaces.rest.dto;

import com.emsafe.occupationalhealth.domain.model.ComplianceReport;

/**
 * Fila del listado de reportes (informe §5.6.2).
 *
 * ## Por qué no reutiliza ComplianceReportResource
 *
 * Aquél serializa las entradas y además llama a `verifyEvidence()`,
 * `isFullyTraceable()` y `entriesWithoutSource()`, que recorren la colección
 * `entries` —lazy y sin `JOIN FETCH` en la consulta del listado—. Usarlo aquí
 * cargaría todas las entradas de todos los reportes de la organización solo
 * para pintar una tabla de metadatos.
 *
 * El digest y la trazabilidad se comprueban al abrir el reporte
 * (`GET /{id}`, que sí trae las entradas con `JOIN FETCH`), que es donde esa
 * información significa algo.
 */
public record ComplianceReportSummaryResource(
        Long id,
        String scope,
        Long subjectWorkerId,
        String periodFrom,
        String periodTo,
        Integer version,
        String generatedAt,
        String exportedAt
) {
    public static ComplianceReportSummaryResource from(ComplianceReport r) {
        return new ComplianceReportSummaryResource(
                r.getId(),
                r.getScope().name(),
                r.getSubjectWorkerId(),
                r.getPeriod().getFrom().toString(),
                r.getPeriod().getTo().toString(),
                r.getVersion(),
                r.getGeneratedAt().toString(),
                r.getExportedAt() != null ? r.getExportedAt().toString() : null);
    }
}
