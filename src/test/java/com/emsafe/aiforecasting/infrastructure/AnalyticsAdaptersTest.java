package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.domain.model.ClosedExposureSession;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade.ClosedSession;
import com.emsafe.plantconfiguration.application.JobPositionService;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Adaptadores de la analítica hacia Personal Dosimetry y Plant Configuration. */
class AnalyticsAdaptersTest {

    private static final Instant FROM = Instant.parse("2026-11-01T05:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-29T05:00:00Z");
    private static final Instant END = Instant.parse("2026-11-10T21:00:00Z");

    private static ClosedSession closed(String id, String unit, String method) {
        return new ClosedSession(id, 101L, 10L, new BigDecimal("9700.5"), new BigDecimal("12000"),
                new BigDecimal("0.800000"), true, false, END.minusSeconds(8 * 3600), END, END.plusSeconds(60),
                new BigDecimal("98.50"), false, unit, method);
    }

    // ─── DosimetryClosedSessionAdapter ──────────────────────────────────────

    private final ExposureQueryFacade facade = mock(ExposureQueryFacade.class);
    private final DosimetryClosedSessionAdapter dosimetry = new DosimetryClosedSessionAdapter(facade);

    @Test
    void mapsTheFacadeRecordToTheDomain() {
        when(facade.closedSessions(8L, FROM, TO)).thenReturn(List.of(closed("S-1", "uT*s", "MAGNETIC_TIME_INDEX_V1")));

        List<ClosedExposureSession> sessions = dosimetry.closedSessions(8L, FROM, TO);

        assertThat(sessions).containsExactly(new ClosedExposureSession("S-1", 101L, 10L, new BigDecimal("9700.5"),
                new BigDecimal("12000"), true, false, END.minusSeconds(8 * 3600), END, false));
    }

    @Test
    void skipsSessionsWithAnotherMethodOrUnit() {
        when(facade.closedSessions(any(), any(), any())).thenReturn(List.of(
                closed("S-ok", "uT*s", "MAGNETIC_TIME_INDEX_V1"),
                closed("S-unit", "mT*s", "MAGNETIC_TIME_INDEX_V1"),
                closed("S-method", "uT*s", "OTHER")));

        assertThat(dosimetry.closedSessions(8L, FROM, TO)).extracting(ClosedExposureSession::sessionId)
                .containsExactly("S-ok");
    }

    // ─── PlantPositionCatalogAdapter ────────────────────────────────────────

    private final JobPositionService positions = mock(JobPositionService.class);
    private final PlantPositionCatalogAdapter catalog = new PlantPositionCatalogAdapter(positions);

    private static JobPositionDto position(Long id, String name) {
        return new JobPositionDto(id, 1L, "Zona A", name, null, null);
    }

    @Test
    void positionNamesById() {
        when(positions.listFor(8L, null)).thenReturn(Arrays.asList(
                position(10L, "Prensa"), position(20L, "Soldadura"), position(30L, null), null));

        assertThat(catalog.positionNames(8L)).containsOnly(
                org.assertj.core.api.Assertions.entry(10L, "Prensa"),
                org.assertj.core.api.Assertions.entry(20L, "Soldadura"));
    }

    @Test
    void aFailingCatalogYieldsNoNamesInsteadOfAnError() {
        when(positions.listFor(any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThat(catalog.positionNames(8L)).isEmpty();
    }
}
