package com.emsafe.occupationalhealth.infrastructure;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.emsafe.occupationalhealth.domain.model.*;
import com.emsafe.occupationalhealth.domain.services.ReportRenderer;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Produce el PDF del reporte de cumplimiento con OpenPDF
 * (informe §5.6.4, PdfReportRenderer).
 *
 * El PDF incluye el digest y, por cada cifra, sus referencias de origen: es lo
 * que hace que el documento entregado se pueda contrastar sin volver al sistema
 * (QAS-07). Un reporte por zona se dibuja con otra tabla, sin ninguna columna
 * que pueda identificar a un operario.
 */
@Component
public class PdfReportRenderer implements ReportRenderer {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD);
    private static final Font HEADING = new Font(Font.HELVETICA, 11, Font.BOLD);
    private static final Font BODY = new Font(Font.HELVETICA, 9);
    private static final Font MONO = new Font(Font.COURIER, 7);

    @Override
    public byte[] render(ComplianceReport report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 40, 40);
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            writeHeader(document, report);
            if (report.getScope() == ReportScope.WORKER) {
                writeWorkerEntries(document, report);
            } else {
                writeZoneEntries(document, report);
            }
            writeEvidence(document, report);
            document.close();
        } catch (DocumentException ex) {
            throw new IllegalStateException(
                    "Could not render report " + report.getId(), ex);
        }
        return out.toByteArray();
    }

    @Override
    public String fileExtension() {
        return "pdf";
    }

    @Override
    public String contentType() {
        return "application/pdf";
    }

    private void writeHeader(Document doc, ComplianceReport r) throws DocumentException {
        doc.add(new Paragraph("EMSafe — Reporte de cumplimiento", TITLE));
        doc.add(new Paragraph(" ", BODY));

        PdfPTable meta = new PdfPTable(2);
        meta.setWidthPercentage(100);
        meta.setWidths(new float[]{1f, 3f});
        addMeta(meta, "Alcance", r.getScope().name());
        if (r.getScope() == ReportScope.WORKER) {
            addMeta(meta, "Trabajador", String.valueOf(r.getSubjectWorkerId()));
        }
        addMeta(meta, "Período", r.getPeriod().getFrom() + " a " + r.getPeriod().getTo()
                + " (" + r.getPeriod().days() + " días)");
        addMeta(meta, "Versión", String.valueOf(r.getVersion()));
        addMeta(meta, "Generado", r.getGeneratedAt().format(STAMP)
                + " por el usuario " + r.getGeneratedBy());
        addMeta(meta, "Entradas", String.valueOf(r.getEntries().size()));
        doc.add(meta);
        doc.add(new Paragraph(" ", BODY));
    }

    private void writeWorkerEntries(Document doc, ComplianceReport r) throws DocumentException {
        doc.add(new Paragraph("Exposición por jornada", HEADING));
        doc.add(new Paragraph(" ", BODY));

        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{1.2f, 1.6f, 1f, 1f, 3f});
        header(table, "Fecha", "Jornada", "Dosis", "% límite", "Origen");

        for (ReportEntry e : r.getEntries()) {
            cell(table, e.getEntryDate() != null ? e.getEntryDate().toString() : "—");
            cell(table, e.getSessionId() != null ? e.getSessionId() : "—");
            cell(table, dose(e));
            cell(table, percentage(e));
            cell(table, sources(e.sourceReferences()));
        }
        doc.add(table);
        writeTraceNotes(doc, r);
    }

    private void writeZoneEntries(Document doc, ComplianceReport r) throws DocumentException {
        doc.add(new Paragraph("Exposición agregada por zona", HEADING));
        doc.add(new Paragraph(
                "Reporte consolidado: no contiene datos individuales de trabajadores.", BODY));
        doc.add(new Paragraph(" ", BODY));

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        header(table, "Zona", "Promedio (µT)", "Máximo (µT)", "Lecturas");

        for (ReportEntry e : r.getEntries()) {
            ZoneAggregate z = e.getZoneAggregate();
            cell(table, z != null ? String.valueOf(z.getZoneId()) : "—");
            cell(table, z != null && z.getAverageValue() != null
                    ? z.getAverageValue().toPlainString() : "—");
            cell(table, z != null && z.getMaxValue() != null
                    ? z.getMaxValue().toPlainString() : "—");
            cell(table, z != null && z.getReadingCount() != null
                    ? String.valueOf(z.getReadingCount()) : "—");
        }
        doc.add(table);
    }

    /** Las notas de trazabilidad van juntas al final, no dentro de la tabla. */
    private void writeTraceNotes(Document doc, ComplianceReport r) throws DocumentException {
        List<String> notes = r.getEntries().stream()
                .flatMap(e -> e.getTraceNotes().stream()
                        .map(n -> (e.getSessionId() != null ? e.getSessionId() : "—")
                                + ": " + n.getCause()))
                .toList();
        if (notes.isEmpty()) return;

        doc.add(new Paragraph(" ", BODY));
        doc.add(new Paragraph("Notas de trazabilidad", HEADING));
        for (String note : notes) {
            doc.add(new Paragraph("• " + note, BODY));
        }
    }

    private void writeEvidence(Document doc, ComplianceReport r) throws DocumentException {
        doc.add(new Paragraph(" ", BODY));
        doc.add(new Paragraph("Evidencia", HEADING));
        doc.add(new Paragraph(r.getEvidenceDigest().getAlgorithm() + ": "
                + r.getEvidenceDigest().getValue(), MONO));

        long missing = r.entriesWithoutSource();
        doc.add(new Paragraph(missing == 0
                ? "Todas las cifras de este reporte tienen al menos una referencia de origen."
                : missing + " entrada(s) sin referencia de origen.", BODY));
    }

    // ─── Ayudas de dibujo ────────────────────────────────────────────────────

    private void addMeta(PdfPTable table, String label, String value) {
        PdfPCell l = new PdfPCell(new Phrase(label, HEADING));
        l.setBorder(Rectangle.NO_BORDER);
        PdfPCell v = new PdfPCell(new Phrase(value, BODY));
        v.setBorder(Rectangle.NO_BORDER);
        table.addCell(l);
        table.addCell(v);
    }

    private void header(PdfPTable table, String... labels) {
        for (String label : labels) {
            PdfPCell cell = new PdfPCell(new Phrase(label, HEADING));
            cell.setBackgroundColor(java.awt.Color.LIGHT_GRAY);
            table.addCell(cell);
        }
    }

    private void cell(PdfPTable table, String text) {
        table.addCell(new PdfPCell(new Phrase(text, BODY)));
    }

    private String dose(ReportEntry e) {
        DoseSnapshot d = e.getFinalDose();
        if (d == null || d.getValue() == null) return "—";
        return d.getValue().toPlainString() + " " + (d.getUnit() != null ? d.getUnit() : "");
    }

    private String percentage(ReportEntry e) {
        DoseSnapshot d = e.getFinalDose();
        return (d == null || d.getPercentage() == null) ? "—" : d.getPercentage() + " %";
    }

    private String sources(List<ReportEntrySource> sources) {
        if (sources.isEmpty()) return "SIN REFERENCIA";
        StringBuilder sb = new StringBuilder();
        sources.forEach(s -> sb.append(s.getType()).append(' ').append(s.getRef()).append("  "));
        return sb.toString().trim();
    }
}
