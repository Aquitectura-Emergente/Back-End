package com.emsafe.radiationmonitoring.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RadiationReadingServiceTest {

    @Test
    void homeReadingsAreClassifiedAndASaturatedOneIsAlwaysDanger() {
        assertThat(RadiationReadingService.classify(50, false)).isEqualTo("SAFE");
        assertThat(RadiationReadingService.classify(150, false)).isEqualTo("CAUTION");
        assertThat(RadiationReadingService.classify(219.5, false)).isEqualTo("DANGER");
        assertThat(RadiationReadingService.classify(10, true)).isEqualTo("DANGER");
    }
}
