package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Copia local del acuse y el escalamiento de una alerta (informe §5.6.1,
 * entidad de proyección).
 *
 * Este contexto no decide ni despacha alertas: solo incorpora como evidencia lo
 * que publica Alerts & Notification Dispatching (RF25, US60). Un registro con
 * escalamiento y sin acuse es el que sube la prioridad de la propuesta del
 * operario, según la regla 10.
 *
 * Solo cuentan las alertas cloud. Los avisos locales del dosímetro no generan
 * evidencia aquí, y las alertas que llegan tarde conservan la hora real del
 * suceso: por eso acknowledgedAt y escalatedAt vienen en el evento y no se
 * sellan con el reloj local.
 */
@Entity
@Table(name = "alert_evidence_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AlertEvidenceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "alert_id", nullable = false, length = 64)
    private String alertId;

    @Column(name = "worker_id")
    private Long workerId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "alert_type", length = 50)
    private String alertType;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    // ─── Comportamiento ───────────────────────────────────────────────────────

    /**
     * Sella el acuse del operario conservando la hora real del suceso. Es
     * idempotente: una alerta reenviada no mueve el acuse original, que es lo
     * que hace que una alerta tardía se notifique una sola vez.
     */
    public void acknowledgeAt(LocalDateTime moment) {
        if (acknowledgedAt == null) {
            this.acknowledgedAt = moment;
        }
    }

    /** Sella el escalamiento al Responsable de SSO, también una sola vez. */
    public void escalateAt(LocalDateTime moment) {
        if (escalatedAt == null) {
            this.escalatedAt = moment;
        }
    }

    /**
     * Escalada sin que el operario la acusara. Es la condición exacta de la
     * regla 10: la propuesta de ese operario pasa a prioridad alta.
     */
    public boolean isEscalatedWithoutAcknowledgement() {
        return escalatedAt != null && acknowledgedAt == null;
    }
}
