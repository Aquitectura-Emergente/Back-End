package com.emsafe.devicemanagement.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MagneticFluxDensityTest {

    @Test
    void theBinaryNoiseOfADoubleIsRoundedToTheStoredScale() {
        assertThat(MagneticFluxDensity.ofMicrotesla(0.33999999761581421).microtesla())
                .isEqualByComparingTo("0.340")
                .hasScaleOf(3);
        assertThat(MagneticFluxDensity.ofMicrotesla(454.94750976562).microtesla()).isEqualByComparingTo("454.948");
    }

    @Test
    void aValueWithFewDecimalsStaysTheSame() {
        assertThat(new MagneticFluxDensity(new BigDecimal("13.5")).microtesla()).isEqualByComparingTo("13.500");
    }
}
