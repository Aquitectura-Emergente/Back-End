package com.emsafe.plantconfiguration.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlantZoneTest {

    @Test
    @DisplayName("una zona necesita organizacion")
    void organizationIsRequired() {
        assertThatThrownBy(() -> PlantZone.create(null, "Nave de Hornos", null))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("requires an organization");
    }

    @Test
    @DisplayName("el nombre no puede estar vacio ni ser solo espacios")
    void nameIsRequired() {
        assertThatThrownBy(() -> PlantZone.create(1L, null, null))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("name is required");

        assertThatThrownBy(() -> PlantZone.create(1L, "   ", null))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    @DisplayName("el nombre se normaliza en el agregado, no en el servicio")
    void nameIsTrimmed() {
        PlantZone zone = PlantZone.create(1L, "  Nave de Hornos  ", "  con horno  ");

        assertThat(zone.getName()).isEqualTo("Nave de Hornos");
        assertThat(zone.getDescription()).isEqualTo("con horno");
    }

    @Test
    @DisplayName("una descripcion vacia se guarda como null, no como cadena vacia")
    void blankDescriptionBecomesNull() {
        PlantZone zone = PlantZone.create(1L, "Zona", "   ");

        assertThat(zone.getDescription()).isNull();
    }

    @Test
    @DisplayName("el nombre no excede el largo de la columna")
    void nameLengthIsBounded() {
        String tooLong = "z".repeat(151);

        assertThatThrownBy(() -> PlantZone.create(1L, tooLong, null))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("cannot exceed 150");
    }

    @Test
    @DisplayName("renombrar aplica las mismas reglas que crear")
    void renameValidates() {
        PlantZone zone = PlantZone.create(1L, "Zona", null);

        zone.rename("  Otra Zona  ");
        assertThat(zone.getName()).isEqualTo("Otra Zona");

        assertThatThrownBy(() -> zone.rename(""))
                .isInstanceOf(PlantConfigurationRuleException.class);
    }

    @Test
    @DisplayName("belongsTo aisla las plantas entre si")
    void belongsToIsolatesOrganizations() {
        PlantZone zone = PlantZone.create(7L, "Zona", null);

        assertThat(zone.belongsTo(7L)).isTrue();
        assertThat(zone.belongsTo(8L)).isFalse();
        assertThat(zone.belongsTo(null)).isFalse();
    }
}
