package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.personaldosimetry.application.ExposureQueryFacade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * El adaptador que reemplazó al cálculo provisional en uT.h (2026-11-22): la
 * cifra y el límite ahora son los de Personal Dosimetry, en uT*s.
 */
class PersonalDosimetryExposureAdapterTest {

    private final ExposureQueryFacade facade = mock(ExposureQueryFacade.class);
    private final PersonalDosimetryExposureAdapter adapter = new PersonalDosimetryExposureAdapter(facade);

    @Test
    void usesTheDoseAndLimitOfPersonalDosimetry() {
        when(facade.currentExposure(1L, 7L)).thenReturn(Optional.of(new ExposureQueryFacade.CurrentExposure(
                "s-1", new BigDecimal("1440000.123456789012"), new BigDecimal("2880000"),
                "uT*s", "MAGNETIC_TIME_INDEX_V1", 4L, Instant.now())));

        var dose = adapter.currentExposure(1L, 7L);

        assertThat(dose.getValue()).isEqualByComparingTo("1440000.1235");
        assertThat(dose.getValue().scale()).as("la columna es DECIMAL(12,4)").isEqualTo(4);
        assertThat(dose.getLimit()).isEqualByComparingTo("2880000");
        assertThat(dose.getUnit()).isEqualTo("uT*s");
        assertThat(dose.getMethodId()).isEqualTo("MAGNETIC_TIME_INDEX_V1");
        assertThat(dose.getPercentage()).isEqualByComparingTo("50.00");
        assertThat(dose.isUnknown()).isFalse();
    }

    @Test
    void withoutAnOpenSessionTheSnapshotIsUnknownButNeverNull() {
        when(facade.currentExposure(1L, 7L)).thenReturn(Optional.empty());

        var dose = adapter.currentExposure(1L, 7L);

        assertThat(dose).isNotNull();
        assertThat(dose.isUnknown()).isTrue();
        assertThat(dose.getUnit()).isEqualTo("uT*s");
        assertThat(dose.hasMargin()).as("sin dato no bloquea la decisión del SSO").isTrue();
    }

    @Test
    void doesNotUseTheProvisionalLimitAnymore() {
        when(facade.currentExposure(1L, 7L)).thenReturn(Optional.of(new ExposureQueryFacade.CurrentExposure(
                "s-1", new BigDecimal("900"), new BigDecimal("1000"), "uT*s",
                "MAGNETIC_TIME_INDEX_V1", 4L, Instant.now())));

        assertThat(adapter.currentExposure(1L, 7L).getLimit())
                .as("antes era el 800 de relleno").isEqualByComparingTo(BigDecimal.valueOf(1000));
        verify(facade).currentExposure(1L, 7L);
    }
}
