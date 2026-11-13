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
 * Puesto de trabajo: el sitio concreto donde está un operario durante su turno.
 *
 * <p>Vive <b>dentro de una zona</b> ({@code plant_zone_id}), que es lo que da la
 * jerarquía planta → zona → puesto. La organización no se guarda aquí: se deriva
 * de la zona, para que no puedan discrepar.
 *
 * <p>Lo referencian {@code occupationalhealth} (turnos y asignaciones) y
 * {@code personaldosimetry} (límites ocupacionales por puesto). Hasta la V22 era
 * un entero sin respaldo: se podía abrir un turno en el puesto 99999 y eso
 * acababa afirmado en un reporte de cumplimiento.
 */
@Entity
@Table(name = "job_positions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plant_zone_id", nullable = false)
    private Long plantZoneId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 400)
    private String description;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public static JobPosition create(Long plantZoneId, String name, String description) {
        if (plantZoneId == null) {
            throw new PlantConfigurationRuleException("A job position requires a plant zone");
        }
        return JobPosition.builder()
                .plantZoneId(plantZoneId)
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

    /** Mover un puesto de zona: la reorganización de una planta es legítima. */
    public void moveTo(Long newPlantZoneId) {
        if (newPlantZoneId == null) {
            throw new PlantConfigurationRuleException("A job position requires a plant zone");
        }
        this.plantZoneId = newPlantZoneId;
    }

    private static String requireName(String value) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new PlantConfigurationRuleException("Job position name is required");
        }
        if (trimmed.length() > 150) {
            throw new PlantConfigurationRuleException(
                    "Job position name cannot exceed 150 characters");
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
