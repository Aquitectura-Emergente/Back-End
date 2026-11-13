package com.emsafe.plantconfiguration.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Zona de una planta industrial: un área física de la fábrica.
 *
 * <p>Es la raíz del agregado de configuración de planta. Una zona agrupa los
 * dispositivos IoT que la vigilan, los puestos de trabajo que contiene y el
 * edge que la conecta al servidor (uno por zona).
 *
 * <p>Antes de este contexto una "zona" era texto libre en {@code devices.location}
 * y un entero sin respaldo en {@code position_assignments.plant_zone_id}. Dos
 * sensores en la misma zona no estaban relacionados por nada, y un turno podía
 * referenciar una zona inexistente.
 *
 * <p>La organización es la cuenta CLIENT de la empresa, igual que en el resto
 * del segmento industrial (V13).
 */
@Entity
@Table(name = "plant_zones")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlantZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 400)
    private String description;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Crea una zona para una organización. El nombre se normaliza aquí y no en
     * el servicio: es la invariante de la zona, no del caso de uso.
     */
    public static PlantZone create(Long organizationId, String name, String description) {
        if (organizationId == null) {
            throw new PlantConfigurationRuleException("A plant zone requires an organization");
        }
        return PlantZone.builder()
                .organizationId(organizationId)
                .name(requireName(name))
                .description(trimToNull(description))
                .createdAt(LocalDateTime.now())
                .build();
    }

    public void rename(String newName) {
        this.name = requireName(newName);
    }

    public void describeAs(String newDescription) {
        this.description = trimToNull(newDescription);
    }

    /** Pertenece a la organización indicada. Base del aislamiento entre plantas. */
    public boolean belongsTo(Long organization) {
        return organizationId != null && organizationId.equals(organization);
    }

    private static String requireName(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new PlantConfigurationRuleException("Plant zone name is required");
        }
        if (trimmed.length() > 150) {
            throw new PlantConfigurationRuleException(
                    "Plant zone name cannot exceed 150 characters");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
