package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Una entrada de la bitácora de transiciones (informe §5.6.6.2,
 * mitigation_action_history).
 *
 * Es append-only: la aplicación nunca hace UPDATE ni DELETE sobre esta tabla.
 * No tiene setters públicos a propósito — una vez escrita, una transición no se
 * corrige, porque es la evidencia de lo que pasó.
 */
@Entity
@Table(name = "mitigation_action_history")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MitigationActionTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mitigation_action_id", nullable = false)
    private MitigationAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 20)
    private MitigationState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 20)
    private MitigationState toState;

    /** Quién la provocó. Nulo cuando la transición la causó un evento, no una persona. */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
