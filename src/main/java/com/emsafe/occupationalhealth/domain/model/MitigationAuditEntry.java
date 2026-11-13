package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Una línea del registro de auditoría (informe §5.6.4, mitigation_audit_log).
 *
 * Guarda actor, recurso, acción, resultado y fecha — y **nunca valores de
 * exposición**. El informe lo pide explícitamente: la exposición de un operario
 * es dato de salud, y un registro de auditoría que la copiara multiplicaría el
 * número de sitios donde hay que protegerla.
 *
 * Sin setters: una línea de auditoría no se corrige. Solo RetentionJob la borra,
 * y únicamente cuando vence su plazo.
 */
@Entity
@Table(name = "mitigation_audit_log")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MitigationAuditEntry {

    /** Resultados posibles. El CHECK de V14 solo admite estos dos. */
    public static final String GRANTED = "GRANTED";
    public static final String DENIED = "DENIED";

    /** Acciones auditadas. */
    public static final String ACTION_APPROVE = "APPROVE_MITIGATION";
    public static final String ACTION_REJECT = "REJECT_MITIGATION";
    public static final String ACTION_RELOCATE = "REGISTER_RELOCATION";
    public static final String ACTION_GENERATE_REPORT = "GENERATE_REPORT";
    public static final String ACTION_EXPORT_REPORT = "EXPORT_REPORT";
    public static final String ACTION_READ = "READ";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nula cuando se deniega a una cuenta que no tiene organización asignada. */
    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "resource_type", nullable = false, length = 40)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(nullable = false, length = 20)
    private String outcome;

    @Column(length = 300)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();

    public boolean isDenial() {
        return DENIED.equals(outcome);
    }
}
