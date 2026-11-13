package com.emsafe.alerts.domain.model;

import com.emsafe.shared.exception.BadRequestException;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** danger | info | success | warning */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(length = 50)
    private String icon;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String relativeTime;

    /** all | specific — who the alarm is addressed to. */
    @Column(name = "recipient_type", length = 30)
    private String recipientType;

    /** Denormalized client name(s) for display in the alarm detail. */
    @Column(name = "client_name", length = 300)
    private String clientName;

    /** Sensor referenced by the alarm (for the detail view). */
    @Column(length = 100)
    private String sensor;

    /** Specific client recipients (user ids). Empty when recipientType = all. */
    @ElementCollection
    @CollectionTable(name = "alert_recipient_clients",
            joinColumns = @JoinColumn(name = "alert_id"))
    @Column(name = "client_id")
    @Builder.Default
    private List<Long> recipientClientIds = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    @Column
    private LocalDateTime resolvedAt;

    // ─── Alerta de operario (V23) ─────────────────────────────────────────────
    // Hasta V22 este agregado solo conocía la alarma dirigida a CLIENTES. Estos
    // siete campos son la alerta del OPERARIO, de la que Occupational Health
    // deja evidencia. Van nulos en una alarma de cliente.

    /** Empresa a la que pertenece el operario. Exigido por el sobre del evento. */
    @Column(name = "organization_id")
    private Long organizationId;

    /** Operario al que concierne. Su presencia es lo que hace a la alerta "de operario". */
    @Column(name = "worker_id")
    private Long workerId;

    /** Jornada de exposición de la que proviene, si proviene de una. */
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "sso_responsible_id")
    private Long ssoResponsibleId;

    @Column(name = "escalation_reason", length = 500)
    private String escalationReason;

    // ─── Comportamiento del agregado ──────────────────────────────────────────

    /** Destinatarios posibles de una alarma. */
    public static final String TO_ALL = "all";
    public static final String TO_SPECIFIC = "specific";

    /**
     * Marca la alarma como atendida. Resolver y sellar la fecha son una sola
     * operación: no debería existir una alarma resuelta sin resolvedAt.
     * Reintentar no mueve la fecha original.
     */
    public void resolve() {
        if (Boolean.TRUE.equals(resolved)) return;
        this.resolved = true;
        this.resolvedAt = LocalDateTime.now();
    }

    /**
     * Una alerta es de operario cuando concierne a uno. Es lo que la distingue de
     * la alarma de cliente, que es todo lo que este contexto conocía hasta V22.
     */
    public boolean concernsAWorker() {
        return workerId != null;
    }

    /**
     * El operario acusa recibo del aviso.
     *
     * <p>Idempotente, igual que {@link #resolve()}: reintentar no mueve la fecha
     * original. Importa más aquí que allí, porque esta marca es evidencia de
     * cumplimiento y moverla falsearía cuándo se enteró el operario.</p>
     *
     * @param at hora REAL del acuse, no la de llegada: un acuse sincronizado de
     *           forma diferida debe constar cuándo ocurrió
     * @return true si esta llamada es la que lo acusó, false si ya estaba acusado
     */
    public boolean acknowledge(LocalDateTime at) {
        requireWorkerAlert("acknowledged");
        if (acknowledgedAt != null) return false;
        this.acknowledgedAt = at;
        return true;
    }

    /**
     * El aviso se escala al Responsable de SSO.
     *
     * <p><b>No exige acuse previo, y eso es el punto.</b> Un escalamiento sin
     * acuse es justo el caso que hace que Occupational Health suba la prioridad
     * de la propuesta del operario (regla 10 del informe): si se exigiera acusar
     * antes, ese caso no podría existir.</p>
     *
     * @return true si esta llamada es la que lo escaló
     */
    public boolean escalate(Long ssoResponsibleId, String reason, LocalDateTime at) {
        requireWorkerAlert("escalated");
        if (ssoResponsibleId == null) {
            throw new BadRequestException("ssoResponsibleId is required to escalate an alert");
        }
        if (escalatedAt != null) return false;
        this.escalatedAt = at;
        this.ssoResponsibleId = ssoResponsibleId;
        this.escalationReason = reason;
        return true;
    }

    /**
     * Las dos marcas solo tienen sentido sobre una alerta de operario: la
     * evidencia de Occupational Health se archiva por operario, y sin
     * {@code workerId} no habría a quién atribuirla.
     *
     * <p>Se exige también la organización, y no es un extra: el registro de
     * evidencia del destino la tiene como {@code NOT NULL}. Sin ella la marca se
     * sellaría aquí y la evidencia fallaría al otro lado, donde el fallo se
     * registra y no se propaga — o sea, se perdería en silencio. Mejor negarlo
     * desde el principio.</p>
     */
    private void requireWorkerAlert(String action) {
        if (!concernsAWorker()) {
            throw new BadRequestException(
                    "Only alerts addressed to a worker can be " + action);
        }
        if (organizationId == null) {
            throw new BadRequestException(
                    "A worker alert needs an organizationId before it can be " + action);
        }
    }

    public boolean isForSpecificClients() {
        return TO_SPECIFIC.equalsIgnoreCase(recipientType);
    }

    /** Dirige la alarma a clientes concretos, dejando el nombre para mostrar. */
    public void addressTo(List<Long> clientIds, String displayNames) {
        this.recipientType = TO_SPECIFIC;
        this.recipientClientIds.addAll(clientIds);
        this.clientName = displayNames;
    }

    public void addressToEveryone() {
        this.recipientType = TO_ALL;
        this.clientName = "All clients";
    }
}
