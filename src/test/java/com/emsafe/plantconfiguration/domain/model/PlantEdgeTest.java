package com.emsafe.plantconfiguration.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlantEdgeTest {

    @Test
    @DisplayName("un edge necesita zona")
    void zoneIsRequired() {
        assertThatThrownBy(() -> PlantEdge.declare(null, "EMSAFE-EDGE-Z1"))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("requires a plant zone");
    }

    @Test
    @DisplayName("el serial es obligatorio y se normaliza")
    void serialIsRequiredAndTrimmed() {
        assertThatThrownBy(() -> PlantEdge.declare(1L, "  "))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("serial number is required");

        PlantEdge edge = PlantEdge.declare(1L, "  EMSAFE-EDGE-Z1  ");
        assertThat(edge.getSerialNumber()).isEqualTo("EMSAFE-EDGE-Z1");
    }

    @Test
    @DisplayName("un edge nace activo")
    void startsActive() {
        PlantEdge edge = PlantEdge.declare(1L, "EMSAFE-EDGE-Z1");

        assertThat(edge.isActive()).isTrue();
        assertThat(edge.getStatus()).isEqualTo(EdgeStatus.ACTIVE);
    }

    @Test
    @DisplayName("activar y desactivar son idempotentes")
    void activationIsIdempotent() {
        PlantEdge edge = PlantEdge.declare(1L, "EMSAFE-EDGE-Z1");

        edge.deactivate();
        edge.deactivate();
        assertThat(edge.isActive()).isFalse();

        edge.activate();
        edge.activate();
        assertThat(edge.isActive()).isTrue();
    }

    @Test
    @DisplayName("cambiar el serial valida igual que declararlo")
    void changeSerialValidates() {
        PlantEdge edge = PlantEdge.declare(1L, "EMSAFE-EDGE-Z1");

        edge.changeSerial("  EMSAFE-EDGE-Z9 ");
        assertThat(edge.getSerialNumber()).isEqualTo("EMSAFE-EDGE-Z9");

        assertThatThrownBy(() -> edge.changeSerial(null))
                .isInstanceOf(PlantConfigurationRuleException.class);
    }

    @Test
    @DisplayName("EdgeStatus solo admite los dos valores del CHECK de la migracion")
    void statusValuesMatchTheSchema() {
        assertThat(EdgeStatus.isValid(EdgeStatus.ACTIVE)).isTrue();
        assertThat(EdgeStatus.isValid(EdgeStatus.INACTIVE)).isTrue();
        assertThat(EdgeStatus.isValid("RETIRED")).isFalse();
        assertThat(EdgeStatus.isValid(null)).isFalse();
    }
}
