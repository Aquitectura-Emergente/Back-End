package com.emsafe.plantconfiguration.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobPositionTest {

    @Test
    @DisplayName("un puesto vive dentro de una zona")
    void zoneIsRequired() {
        assertThatThrownBy(() -> JobPosition.create(null, "Soldadura", null))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("requires a plant zone");
    }

    @Test
    @DisplayName("el nombre es obligatorio y se normaliza")
    void nameIsRequiredAndTrimmed() {
        assertThatThrownBy(() -> JobPosition.create(1L, "  ", null))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("name is required");

        JobPosition position = JobPosition.create(1L, "  Soldadura linea 2 ", null);
        assertThat(position.getName()).isEqualTo("Soldadura linea 2");
    }

    @Test
    @DisplayName("el nombre no excede el largo de la columna")
    void nameLengthIsBounded() {
        assertThatThrownBy(() -> JobPosition.create(1L, "p".repeat(151), null))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("cannot exceed 150");
    }

    @Test
    @DisplayName("un puesto se puede mover de zona: reorganizar una planta es legitimo")
    void canMoveBetweenZones() {
        JobPosition position = JobPosition.create(1L, "Soldadura", null);

        position.moveTo(2L);

        assertThat(position.getPlantZoneId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("mover a ninguna zona no es mover: es dejarlo huerfano")
    void cannotMoveToNoZone() {
        JobPosition position = JobPosition.create(1L, "Soldadura", null);

        assertThatThrownBy(() -> position.moveTo(null))
                .isInstanceOf(PlantConfigurationRuleException.class)
                .hasMessageContaining("requires a plant zone");
        assertThat(position.getPlantZoneId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("la regla de dominio responde 400 por heredar de BadRequestException")
    void ruleExceptionMapsTo400() {
        // El estilo elegido para este contexto es el del CLAUDE.md: excepciones de
        // shared resueltas en GlobalExceptionHandler. Si alguien cambia la
        // jerarquia, el contrato HTTP cambia en silencio y este test lo caza.
        assertThat(new PlantConfigurationRuleException("x"))
                .isInstanceOf(com.emsafe.shared.exception.BadRequestException.class);
    }
}
