package com.emsafe.plantconfiguration.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PlantEdgeConnectivityTest {

    @Test
    void connectivityIsSeparateFromTheAdministrativeStatusAndIgnoresOlderStates() {
        PlantEdge edge = PlantEdge.declare(1L, "EDGE-PLANTA-01");
        Instant t = Instant.parse("2026-11-12T15:00:00Z");
        assertThat(edge.recordConnectivity(true, t)).isTrue();
        assertThat(edge.recordConnectivity(false, t.minusSeconds(5))).isFalse();
        assertThat(edge.getConnectivity()).isEqualTo("ONLINE");
        assertThat(edge.recordConnectivity(false, t.plusSeconds(5))).isTrue();
        assertThat(edge.getConnectivity()).isEqualTo("OFFLINE");
        assertThat(edge.isActive()).isTrue();
    }
}
