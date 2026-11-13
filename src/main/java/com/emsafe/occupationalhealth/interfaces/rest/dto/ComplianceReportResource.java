package com.emsafe.occupationalhealth.interfaces.rest.dto;

import com.emsafe.occupationalhealth.domain.model.ComplianceReport;
import com.emsafe.occupationalhealth.domain.model.ReportEntry;
import com.emsafe.occupationalhealth.domain.model.ReportEntrySource;
import com.emsafe.occupationalhealth.domain.model.TraceabilityNote;
import com.emsafe.occupationalhealth.domain.model.ZoneAggregate;

import java.math.BigDecimal;
import java.util.List;

/**
 * Metadatos y contenido de un reporte (informe §5.6.2).
 *
 * `evidenceVerified` se calcula al serializar: recalcula el digest sobre el
 * contenido guardado y lo compara con el sellado. Es la comprobación que
 * demuestra que el reporte no se tocó después de emitirse, y viajarla en la
 * respuesta evita que haya que confiar en que alguien la haga.
 */
public record ComplianceReportResource(
        Long id,
        String scope,
        Long subjectWorkerId,
        String periodFrom,
        String periodTo,
        Integer version,
        String digestAlgorithm,
        String digestValue,
        boolean evidenceVerified,
        boolean fullyTraceable,
        long entriesWithoutSource,
        String fileReference,
        String exportedAt,
        Long generatedBy,
        String generatedAt,
        List<EntryResource> entries
) {
    public static ComplianceReportResource from(ComplianceReport r) {
        return new ComplianceReportResource(
                r.getId(),
                r.getScope().name(),
                r.getSubjectWorkerId(),
                r.getPeriod().getFrom().toString(),
                r.getPeriod().getTo().toString(),
                r.getVersion(),
                r.getEvidenceDigest().getAlgorithm(),
                r.getEvidenceDigest().getValue(),
                r.verifyEvidence(),
                r.isFullyTraceable(),
                r.entriesWithoutSource(),
                r.getFileReference(),
                r.getExportedAt() != null ? r.getExportedAt().toString() : null,
                r.getGeneratedBy(),
                r.getGeneratedAt().toString(),
                r.getEntries().stream().map(EntryResource::from).toList());
    }

    public record EntryResource(
            Long id,
            String sessionId,
            String entryDate,
            DoseSnapshotResource finalDose,
            ZoneAggregateResource zone,
            List<SourceResource> sources,
            List<String> traceNotes
    ) {
        static EntryResource from(ReportEntry e) {
            return new EntryResource(
                    e.getId(),
                    e.getSessionId(),
                    e.getEntryDate() != null ? e.getEntryDate().toString() : null,
                    DoseSnapshotResource.from(e.getFinalDose()),
                    ZoneAggregateResource.from(e.getZoneAggregate()),
                    e.sourceReferences().stream().map(SourceResource::from).toList(),
                    e.getTraceNotes().stream().map(TraceabilityNote::getCause).toList());
        }
    }

    public record SourceResource(String type, String ref) {
        static SourceResource from(ReportEntrySource s) {
            return new SourceResource(s.getType().name(), s.getRef());
        }
    }

    public record ZoneAggregateResource(
            Long zoneId, BigDecimal average, BigDecimal max, Integer readingCount) {
        static ZoneAggregateResource from(ZoneAggregate z) {
            if (z == null) return null;
            return new ZoneAggregateResource(z.getZoneId(), z.getAverageValue(),
                    z.getMaxValue(), z.getReadingCount());
        }
    }
}
