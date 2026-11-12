package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Un operario alcanzado por la medida, con la exposición que tenía cuando se
 * decidió (informe §5.6.1).
 *
 * Conservar aquí la foto de la dosis es lo que permite auditar la decisión: sin
 * ella habría que reconstruir qué sabía el SSO en ese momento.
 */
@Entity
@Table(name = "affected_workers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffectedWorker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mitigation_action_id", nullable = false)
    private MitigationAction action;

    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    @Column(name = "from_position_id")
    private Long fromPositionId;

    @Column(name = "to_position_id")
    private Long toPositionId;

    @Column(name = "to_zone_id")
    private Long toZoneId;

    @Embedded
    private DoseSnapshot doseAtDecision;

    // ─── Comportamiento ───────────────────────────────────────────────────────

    /**
     * Comprueba que el destino esté completo para una medida que mueve gente.
     * Una reprogramación no mueve a nadie, así que no exige destino.
     */
    public void validateTarget(MitigationType type) {
        if (!type.movesWorker()) return;
        if (toPositionId == null || toZoneId == null) {
            throw new MitigationRuleException(
                    "A " + type + " requires a target position and zone for worker " + workerId);
        }
    }

    /**
     * Actualiza la foto de exposición con la que se decide, al revalidar en el
     * momento de aprobar (regla 4): la cifra de la sugerencia pudo quedar vieja.
     */
    public void refreshDose(DoseSnapshot current) {
        this.doseAtDecision = current;
    }

    public boolean hasMargin() {
        return doseAtDecision == null || doseAtDecision.hasMargin();
    }
}
