package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Un tramo del turno en un puesto y una zona determinados (informe §5.6.1).
 *
 * jobPositionId y plantZoneId son identificadores de integración: pertenecen a
 * otros contextos y aquí no se resuelven ni se referencian con foreign key.
 * sourceActionId guarda qué medida originó el tramo, de modo que un turno
 * cuenta por sí solo la historia de por qué el operario acabó donde acabó.
 */
@Entity
@Table(name = "position_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_shift_id", nullable = false)
    private WorkerShift workerShift;

    @Column(name = "job_position_id", nullable = false)
    private Long jobPositionId;

    @Column(name = "plant_zone_id", nullable = false)
    private Long plantZoneId;

    @Embedded
    private ShiftInterval interval;

    /** Medida que abrió este tramo. Nulo en el primer tramo del turno. */
    @Column(name = "source_action_id")
    private Long sourceActionId;

    // ─── Comportamiento ───────────────────────────────────────────────────────

    /**
     * Cierra el tramo en el instante dado. Reintentar no mueve el cierre
     * original: igual que Alert.resolve(), la idempotencia evita que un doble
     * toque en el móvil reescriba una marca ya sellada.
     */
    public void closeAt(LocalDateTime moment) {
        if (!interval.isOpen()) return;
        this.interval = interval.closedAt(moment);
    }

    public boolean isOpen() {
        return interval.isOpen();
    }

    public boolean contains(LocalDateTime moment) {
        return interval.contains(moment);
    }

    public boolean isInZone(Long zoneId) {
        return plantZoneId != null && plantZoneId.equals(zoneId);
    }
}
