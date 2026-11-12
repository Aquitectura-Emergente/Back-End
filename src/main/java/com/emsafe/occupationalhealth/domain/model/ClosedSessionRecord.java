package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Copia local de una jornada de exposición ya consolidada por Personal
 * Dosimetry (informe §5.6.1, entidad de proyección).
 *
 * Existe para que generar un reporte no tenga que consultar a Dosimetry por
 * cada entrada. No es fuente de verdad: se reconstruye desde los eventos
 * ExposureSessionClosed, y si se pierde se vuelve a poblar reprocesándolos.
 *
 * `coverage` viaja tal como lo manda Dosimetry y aquí no se interpreta: sirve
 * para marcar con una TraceabilityNote las entradas con cobertura incompleta.
 */
@Entity
@Table(name = "closed_session_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClosedSessionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    /**
     * La dosis final de la jornada. Se reutiliza DoseSnapshot con la columna
     * renombrada: el informe llama a este campo final_dose, pero el contenido
     * —valor, unidad, método y límite— es exactamente el mismo value object.
     */
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "final_dose", precision = 12, scale = 4))
    private DoseSnapshot finalDose;

    @Column(length = 30)
    private String coverage;

    @Column(name = "finalized_at", nullable = false)
    private LocalDateTime finalizedAt;

    // ─── Comportamiento ───────────────────────────────────────────────────────

    /** Cobertura incompleta: la entrada del reporte llevará una nota de trazabilidad. */
    public boolean hasIncompleteCoverage() {
        return coverage != null && !"COMPLETE".equalsIgnoreCase(coverage.trim());
    }

    /** Si la jornada cae dentro del período pedido, por su fecha de cierre. */
    public boolean finalizedWithin(ReportPeriod period) {
        return period.contains(finalizedAt.toLocalDate());
    }

    /** Superó el límite aplicable: alimenta el recuento de excedencias del reporte. */
    public boolean exceededLimit() {
        return finalDose != null && !finalDose.hasMargin();
    }
}
