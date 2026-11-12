package com.emsafe.occupationalhealth.application.events;

/**
 * Mitigation → auditoría (informe §5.6.3).
 *
 * Anuncia que se emitió un reporte, con su digest. No lleva cifras de
 * exposición: quien necesite el contenido pide el reporte y queda registrado
 * en la auditoría, que es lo que exige tratar la exposición como dato sensible.
 */
public record ComplianceReportGenerated(
        EventEnvelope envelope,
        Long reportId,
        String scope,
        String period,
        Integer version,
        String evidenceDigest) {
}
