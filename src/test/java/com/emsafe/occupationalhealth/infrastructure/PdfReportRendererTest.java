package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.occupationalhealth.domain.model.ClosedSessionRecord;
import com.emsafe.occupationalhealth.domain.model.ComplianceReport;
import com.emsafe.occupationalhealth.domain.model.ComplianceReportFactory;
import com.emsafe.occupationalhealth.domain.model.DoseSnapshot;
import com.emsafe.occupationalhealth.domain.model.ReportPeriod;
import com.emsafe.occupationalhealth.domain.services.ReportNamesPort;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** El PDF de cumplimiento se lee como un documento, no como un volcado de ids. */
class PdfReportRendererTest {

    private static final String SESSION = "632e692d-588d-4b33-b4df-f2923217c41c";

    private final ReportNamesPort names = new ReportNamesPort() {
        public Optional<String> personName(Long id) {
            return id == 18L ? Optional.of("Fabricio Lopez") : id == 11L ? Optional.of("Laura Quispe") : Optional.empty();
        }
        public Optional<String> zoneName(Long id) {
            return Optional.of("Nave 1");
        }
    };

    @Test
    void aWorkerReportShowsNamesReadableNumbersAndShortReferences() throws Exception {
        ClosedSessionRecord session = ClosedSessionRecord.builder()
                .organizationId(8L).sessionId(SESSION).workerId(18L)
                .finalDose(DoseSnapshot.of(new BigDecimal("396819.3697"), "uT*s", "MAGNETIC_TIME_INDEX_V1",
                        new BigDecimal("3000")))
                .coverage("64.67% (incompleta)")
                .finalizedAt(LocalDateTime.of(2026, 11, 27, 21, 30))
                .build();
        ComplianceReport report = new ComplianceReportFactory().forWorker(8L, 18L,
                ReportPeriod.of(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)), 1, 11L,
                List.of(), List.of(session), List.of());

        String text = PdfTextExtractor.class.getDeclaredConstructor(PdfReader.class)
                .newInstance(new PdfReader(new PdfReportRenderer(names).render(report)))
                .getTextFromPage(1);

        assertThat(text)
                .contains("Por trabajador")
                .contains("Fabricio Lopez")
                .contains("por Laura Quispe")
                .contains("396,819 µT·s")
                .contains("632e692d")
                .doesNotContain(SESSION)
                .doesNotContain("WORKER")
                .doesNotContain("uT*s");
    }
}
