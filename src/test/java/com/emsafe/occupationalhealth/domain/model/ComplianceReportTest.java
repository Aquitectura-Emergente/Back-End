package com.emsafe.occupationalhealth.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reglas 7, 8 y 9 del informe §5.6.1: un reporte generado no se modifica, cada
 * cifra referencia su origen, y un reporte por zona no puede identificar a
 * nadie.
 */
class ComplianceReportTest {

    private static final Long ORG = 7L;
    private static final Long WORKER = 11L;

    private final ComplianceReportFactory factory = new ComplianceReportFactory();

    private static ReportPeriod november() {
        return ReportPeriod.of(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
    }

    private static ClosedSessionRecord session(String id, String dose, String coverage) {
        return ClosedSessionRecord.builder()
                .organizationId(ORG)
                .sessionId(id)
                .workerId(WORKER)
                .finalDose(DoseSnapshot.of(new BigDecimal(dose), "uT.h", "M1",
                        new BigDecimal("800")))
                .coverage(coverage)
                .finalizedAt(LocalDateTime.of(2026, 11, 10, 18, 0))
                .build();
    }

    // ── Regla 7: inmutabilidad y versionado ──────────────────────────────────

    @Test
    @DisplayName("una acción desplazada no entra en el reporte")
    void supersededActionIsExcluded() {
        MitigationAction superseded = MitigationAction.propose(ORG, MitigationType.ROTATION,
                MitigationOrigin.MANUAL, MitigationTrigger.manual(), "desplazada");
        MitigationAction replacement = MitigationAction.propose(ORG, MitigationType.RELOCATION,
                MitigationOrigin.MANUAL, MitigationTrigger.manual(), "la nueva");
        replacement.setId(99L);
        superseded.supersedeBy(replacement);

        ComplianceReport report = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(superseded), List.of(session("s1", "400", "COMPLETE")), List.of());

        boolean mentionsIt = report.getEntries().stream()
                .flatMap(e -> e.sourceReferences().stream())
                .anyMatch(s -> s.getType() == ReportEntrySource.SourceType.ACTION);
        assertFalse(mentionsIt,
                "un registro legal a 5 años no puede afirmar una medida que no se tomó");
    }

    @Test
    @DisplayName("un reporte recién sellado verifica su propia evidencia")
    void sealedReportVerifiesItsEvidence() {
        ComplianceReport report = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(), List.of(session("s1", "400", "COMPLETE")), List.of());

        assertNotNull(report.getEvidenceDigest());
        assertEquals(EvidenceDigest.SHA_256, report.getEvidenceDigest().getAlgorithm());
        assertTrue(report.verifyEvidence());
    }

    @Test
    @DisplayName("dos reportes con distinto contenido tienen distinto digest")
    void differentContentYieldsDifferentDigest() {
        ComplianceReport a = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(), List.of(session("s1", "400", "COMPLETE")), List.of());
        ComplianceReport b = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(), List.of(session("s1", "500", "COMPLETE")), List.of());

        assertNotEquals(a.getEvidenceDigest().getValue(), b.getEvidenceDigest().getValue());
    }

    @Test
    @DisplayName("la versión entra en el digest: dos versiones nunca son iguales")
    void versionIsPartOfTheDigest() {
        ComplianceReport v1 = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(), List.of(session("s1", "400", "COMPLETE")), List.of());
        ComplianceReport v2 = factory.forWorker(ORG, WORKER, november(), 2, 99L,
                List.of(), List.of(session("s1", "400", "COMPLETE")), List.of());

        assertNotEquals(v1.getEvidenceDigest().getValue(), v2.getEvidenceDigest().getValue());
    }

    @Test
    @DisplayName("marcar exportado no mueve la marca original")
    void markExportedIsIdempotent() {
        ComplianceReport report = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(), List.of(session("s1", "400", "COMPLETE")), List.of());

        report.markExported("org-7/report-1-v1.pdf");
        LocalDateTime first = report.getExportedAt();
        report.markExported("otra-ruta.pdf");

        assertEquals(first, report.getExportedAt());
        assertEquals("org-7/report-1-v1.pdf", report.getFileReference(),
                "reexportar no reescribe la referencia original");
        assertTrue(report.isExported());
    }

    @Test
    @DisplayName("exportar no altera el contenido ni invalida la evidencia")
    void exportingDoesNotBreakTheDigest() {
        ComplianceReport report = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(), List.of(session("s1", "400", "COMPLETE")), List.of());

        report.markExported("org-7/report-1-v1.pdf");

        assertTrue(report.verifyEvidence(),
                "el digest se calcula sobre el contenido, no sobre el archivo");
    }

    // ── Regla 8: trazabilidad de cada cifra ──────────────────────────────────

    @Test
    @DisplayName("cada entrada referencia al menos su jornada de origen")
    void everyEntryReferencesItsSource() {
        ComplianceReport report = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(),
                List.of(session("s1", "400", "COMPLETE"), session("s2", "600", "COMPLETE")),
                List.of());

        assertEquals(2, report.getEntries().size());
        assertEquals(0, report.entriesWithoutSource());
        assertTrue(report.isFullyTraceable(), "QAS-07 exige el 100 %");
        report.getEntries().forEach(e -> assertTrue(e.hasSourceReference()));
    }

    @Test
    @DisplayName("una jornada con cobertura incompleta lleva nota de trazabilidad")
    void incompleteCoverageAddsATraceNote() {
        ComplianceReport report = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(), List.of(session("s1", "400", "PARTIAL")), List.of());

        ReportEntry entry = report.getEntries().get(0);
        assertEquals(1, entry.getTraceNotes().size());
        assertTrue(entry.getTraceNotes().get(0).getCause()
                .contains(TraceabilityNote.INCOMPLETE_COVERAGE));
    }

    @Test
    @DisplayName("una jornada completa no genera notas")
    void completeCoverageAddsNoNotes() {
        ComplianceReport report = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(), List.of(session("s1", "400", "COMPLETE")), List.of());

        assertTrue(report.getEntries().get(0).getTraceNotes().isEmpty());
    }

    @Test
    @DisplayName("la evidencia de una alerta de la misma jornada entra como referencia")
    void alertEvidenceBecomesASource() {
        AlertEvidenceRecord alert = AlertEvidenceRecord.builder()
                .organizationId(ORG).alertId("a-1").workerId(WORKER).sessionId("s1")
                .alertType("DANGER").build();

        ComplianceReport report = factory.forWorker(ORG, WORKER, november(), 1, 99L,
                List.of(), List.of(session("s1", "400", "COMPLETE")), List.of(alert));

        List<ReportEntrySource> sources = report.getEntries().get(0).sourceReferences();
        assertTrue(sources.stream().anyMatch(
                s -> s.getType() == ReportEntrySource.SourceType.ALERT && "a-1".equals(s.getRef())));
    }

    // ── Regla 9: el reporte por zona no identifica a nadie ───────────────────

    @Test
    @DisplayName("un reporte por zona solo lleva agregados")
    void zoneReportCarriesOnlyAggregates() {
        ComplianceReport report = factory.forPlantZones(ORG, november(), 1, 99L,
                List.of(new ZoneStatistics(5L, new BigDecimal("42.5"),
                        new BigDecimal("180.0"), 120)));

        assertEquals(ReportScope.PLANT_ZONE, report.getScope());
        assertNull(report.getSubjectWorkerId());
        ReportEntry entry = report.getEntries().get(0);
        assertNull(entry.getSessionId(), "no puede arrastrar la jornada de nadie");
        assertNull(entry.getFinalDose());
        assertEquals(5L, entry.getZoneAggregate().getZoneId());
        assertEquals(120, entry.getZoneAggregate().getReadingCount());
    }

    @Test
    @DisplayName("un reporte por zona con trabajador asignado se rechaza")
    void zoneReportCannotTargetAWorker() {
        ComplianceReport report = ComplianceReport.builder()
                .organizationId(ORG)
                .scope(ReportScope.PLANT_ZONE)
                .subjectWorkerId(WORKER)
                .period(november())
                .version(1)
                .generatedBy(99L)
                .build();

        MitigationRuleException ex = assertThrows(MitigationRuleException.class, report::seal);
        assertTrue(ex.getMessage().contains("RF32"));
    }

    @Test
    @DisplayName("un reporte por trabajador sin trabajador se rechaza")
    void workerReportRequiresASubject() {
        ComplianceReport report = ComplianceReport.builder()
                .organizationId(ORG)
                .scope(ReportScope.WORKER)
                .period(november())
                .version(1)
                .generatedBy(99L)
                .build();

        assertThrows(MitigationRuleException.class, report::seal);
    }

    // ── ReportPeriod ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("el período cuenta ambos extremos")
    void periodCountsBothEnds() {
        assertEquals(30, november().days());
        assertEquals(1, ReportPeriod.of(LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 9)).days());
    }

    @Test
    @DisplayName("un período invertido o mayor de 366 días se rechaza con 422")
    void periodInvariants() {
        assertThrows(MitigationUnprocessableException.class,
                () -> ReportPeriod.of(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 1)));
        assertThrows(MitigationUnprocessableException.class,
                () -> ReportPeriod.of(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1)));
    }

    @Test
    @DisplayName("el digest detecta una manipulación del contenido")
    void digestDetectsTampering() {
        EvidenceDigest digest = EvidenceDigest.of("contenido original");

        assertTrue(digest.matches("contenido original"));
        assertFalse(digest.matches("contenido manipulado"));
    }
}
