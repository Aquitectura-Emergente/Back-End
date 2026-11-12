package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Una medida preventiva o correctiva, desde que se propone hasta que se aplica
 * o se rechaza (informe §5.6.1).
 *
 * Es el agregado que sostiene la regla central del contexto: ninguna medida
 * llega a aplicarse sin una decisión humana registrada. Las transiciones están
 * cerradas en MitigationState y cada una deja una entrada en la bitácora, así
 * que el historial no depende de que el servicio se acuerde de escribirlo.
 */
@Entity
@Table(name = "mitigation_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MitigationAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MitigationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MitigationOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MitigationState state = MitigationState.PROPOSED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private MitigationPriority priority = MitigationPriority.NORMAL;

    @Embedded
    private MitigationTrigger trigger;

    @Column(columnDefinition = "TEXT")
    private String justification;

    @Embedded
    private Decision decision;

    /** Qué pasó al final: aplicada, por qué falló, o por qué no era viable. */
    @Column(length = 300)
    private String outcome;

    /** Acción que reemplazó a esta, cuando una sugerencia nueva la desplazó. */
    @Column(name = "superseded_by_id")
    private Long supersededById;

    @OneToMany(mappedBy = "action",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 30)
    @Builder.Default
    private List<AffectedWorker> affectedWorkers = new ArrayList<>();

    @OneToMany(mappedBy = "action",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 30)
    @Builder.Default
    private List<MitigationActionTransition> history = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Version
    private Long version;

    // ─── Fábricas ─────────────────────────────────────────────────────────────

    /**
     * Abre una propuesta. Nace siempre en PROPOSED, sea cual sea el origen:
     * ni una sugerencia del agente ni una excedencia confirmada pueden crear
     * una medida ya decidida (regla 1).
     */
    public static MitigationAction propose(Long organizationId, MitigationType type,
                                          MitigationOrigin origin, MitigationTrigger trigger,
                                          String justification) {
        if (origin.requiresSourceEvent()
                && (trigger == null || trigger.getSourceEventId() == null)) {
            throw new MitigationRuleException("Origin " + origin + " requires a source event");
        }
        MitigationAction action = MitigationAction.builder()
                .organizationId(organizationId)
                .type(type)
                .origin(origin)
                .state(MitigationState.PROPOSED)
                .priority(MitigationPriority.NORMAL)
                .trigger(trigger != null ? trigger : MitigationTrigger.manual())
                .justification(justification)
                .build();
        action.log(null, MitigationState.PROPOSED, null);
        return action;
    }

    /**
     * Propuesta de reprogramación derivada de una rotación sin candidato viable
     * (regla 4). Hereda el disparador original para no perder la trazabilidad
     * de por qué se llegó a reprogramar.
     */
    public static MitigationAction proposeReschedulingFrom(MitigationAction notViable) {
        MitigationAction action = MitigationAction.builder()
                .organizationId(notViable.organizationId)
                .type(MitigationType.RESCHEDULING)
                .origin(notViable.origin)
                .state(MitigationState.PROPOSED)
                .priority(notViable.priority)
                // El trigger NO se copia tal cual: sourceEventId es único por
                // organización y reutilizarlo rompería el UNIQUE que descarta
                // duplicados. Se conserva solo el tipo y el instante del hecho.
                .trigger(MitigationTrigger.manual())
                .justification("No viable rotation candidate for action #" + notViable.id)
                .build();
        action.log(null, MitigationState.PROPOSED, null);
        notViable.affectedWorkers.forEach(w -> action.addAffectedWorker(
                w.getWorkerId(), w.getFromPositionId(), null, null, w.getDoseAtDecision()));
        return action;
    }

    // ─── Comportamiento del agregado ──────────────────────────────────────────

    /**
     * Añade un operario alcanzado. El tope lo fija el tipo: una rotación
     * intercambia a dos personas, reubicar y reprogramar afectan a una.
     */
    public void addAffectedWorker(Long workerId, Long fromPositionId, Long toPositionId,
                                  Long toZoneId, DoseSnapshot doseAtDecision) {
        if (affectedWorkers.size() >= type.maxAffectedWorkers()) {
            throw new MitigationRuleException(
                    "A " + type + " affects at most " + type.maxAffectedWorkers() + " worker(s)");
        }
        AffectedWorker worker = AffectedWorker.builder()
                .organizationId(organizationId)
                .action(this)
                .workerId(workerId)
                .fromPositionId(fromPositionId)
                .toPositionId(toPositionId)
                .toZoneId(toZoneId)
                .doseAtDecision(doseAtDecision)
                .build();
        worker.validateTarget(type);
        affectedWorkers.add(worker);
    }

    /**
     * Añade un operario cuyo destino todavía no se conoce.
     *
     * ── Hueco del informe ─────────────────────────────────────────────────────
     * §5.6.3 dice que una excedencia confirmada "crea una acción PROPOSED de
     * origen LIMIT_EXCEEDED", pero no dice con qué destino — y no puede
     * decirlo: el hecho informa de que hay que sacar al operario de donde está,
     * no a dónde llevarlo. Esa es una decisión del SSO.
     *
     * Por eso la propuesta puede nacer sin zona destino. La validación no se
     * pierde, se traslada: assignTarget() la exige antes de aprobar, y aprobar
     * sin destino falla.
     */
    public void addAffectedWorkerPendingTarget(Long workerId, Long fromPositionId,
                                               DoseSnapshot doseAtDecision) {
        if (state != MitigationState.PROPOSED) {
            throw new MitigationConflictException(
                    "Workers can only be added while the action is PROPOSED");
        }
        if (affectedWorkers.size() >= type.maxAffectedWorkers()) {
            throw new MitigationRuleException(
                    "A " + type + " affects at most " + type.maxAffectedWorkers() + " worker(s)");
        }
        affectedWorkers.add(AffectedWorker.builder()
                .organizationId(organizationId)
                .action(this)
                .workerId(workerId)
                .fromPositionId(fromPositionId)
                .doseAtDecision(doseAtDecision)
                .build());
    }

    /** Si falta por decidir a dónde va alguno de los operarios. */
    public boolean hasPendingTarget() {
        return type.movesWorker() && affectedWorkers.stream()
                .anyMatch(w -> w.getToPositionId() == null || w.getToZoneId() == null);
    }

    /**
     * Fija el destino que el SSO decide al aprobar. Solo rellena lo que falta:
     * una rotación que ya traía destinos del agente no se puede redirigir por
     * esta vía, porque entonces aprobar dejaría de ser aprobar lo propuesto.
     */
    public void assignTarget(Long toPositionId, Long toZoneId) {
        if (state != MitigationState.PROPOSED) {
            throw new MitigationConflictException(
                    "The target can only be set while the action is PROPOSED");
        }
        affectedWorkers.stream()
                .filter(w -> w.getToPositionId() == null || w.getToZoneId() == null)
                .forEach(w -> {
                    w.setToPositionId(toPositionId);
                    w.setToZoneId(toZoneId);
                    w.validateTarget(type);
                });
    }

    /**
     * Registra la decisión a favor. No aplica nada todavía: aplicar es confirmar
     * que la reasignación de turnos ocurrió, y eso lo marca markApplied().
     */
    public void approve(Decision approval) {
        if (approval == null) {
            throw new MitigationRuleException("Approving requires a decision");
        }
        transitionTo(MitigationState.APPROVED, approval.getDecidedBy());
        this.decision = approval;
    }

    /** Registra la decisión en contra. El motivo lo exige Decision.rejectedBy. */
    public void reject(Decision rejection) {
        if (rejection == null) {
            throw new MitigationRuleException("Rejecting requires a decision");
        }
        transitionTo(MitigationState.REJECTED, rejection.getDecidedBy());
        this.decision = rejection;
        this.outcome = rejection.getReason();
    }

    /** La reasignación se confirmó. */
    public void markApplied(String resultNote) {
        transitionTo(MitigationState.APPLIED, decidedBy());
        this.outcome = resultNote;
    }

    /** La reasignación no pudo aplicarse: Dosimetry rechazó la actualización. */
    public void markFailed(String resultNote) {
        transitionTo(MitigationState.FAILED, decidedBy());
        this.outcome = resultNote;
    }

    /**
     * Cierra la propuesta por no haber candidato con margen. Quien la cierra es
     * el sistema, no una persona, así que no lleva Decision: no se está
     * decidiendo una medida sobre la operación, se constata que no hay ninguna
     * que proponer.
     */
    public void declareNotViable(String resultNote) {
        transitionTo(MitigationState.NOT_VIABLE, null);
        this.outcome = resultNote;
    }

    /**
     * Una sugerencia nueva desplaza a esta propuesta (regla 5).
     *
     * Pasa a SUPERSEDED, un estado que el informe §5.6.1 no contempla y que se
     * añadió a propósito: reutilizar NOT_VIABLE haría que un reporte de
     * cumplimiento —registro legal a cinco años— afirmara que no se encontró
     * rotación viable, cuando lo único que pasó es que llegó una propuesta
     * posterior. El motivo queda además en outcome y en superseded_by_id.
     */
    public void supersedeBy(MitigationAction replacement) {
        if (!state.isOpen()) {
            throw new MitigationRuleException(
                    "Only an open action can be superseded, this one is " + state);
        }
        transitionTo(MitigationState.SUPERSEDED, null);
        this.outcome = "Superseded by action #" + replacement.getId();
        this.supersededById = replacement.getId();
    }

    /**
     * Sube la prioridad por un escalamiento al SSO sin acuse del operario
     * (regla 10). Solo afecta a propuestas vivas: escalar una acción ya cerrada
     * no cambia nada, y volver a escalar no hace daño.
     */
    public void raisePriority() {
        if (state.isOpen()) {
            this.priority = MitigationPriority.HIGH;
        }
    }

    /**
     * Revalida el margen de los operarios con la exposición de ahora (regla 4).
     * Devuelve false si alguno ya no tiene margen, caso en el que la acción no
     * debe aprobarse.
     */
    public boolean refreshDosesAndCheckMargin(java.util.function.Function<Long, DoseSnapshot> currentDose) {
        boolean allHaveMargin = true;
        for (AffectedWorker worker : affectedWorkers) {
            worker.refreshDose(currentDose.apply(worker.getWorkerId()));
            if (!worker.hasMargin()) allHaveMargin = false;
        }
        return allHaveMargin;
    }

    public boolean isOpen() {
        return state.isOpen();
    }

    /**
     * Si esta medida compite con otra por el mismo operario. Reprogramar no
     * ocupa al operario, así que no entra en el tope de la regla 5.
     */
    public boolean competesForWorker() {
        return type == MitigationType.ROTATION || type == MitigationType.RELOCATION;
    }

    public List<Long> affectedWorkerIds() {
        return affectedWorkers.stream().map(AffectedWorker::getWorkerId).toList();
    }

    private Long decidedBy() {
        return decision != null ? decision.getDecidedBy() : null;
    }

    /**
     * Único camino para cambiar de estado: valida la transición y la apunta en
     * la bitácora. Si se pudiera esquivar con un setter, la regla estaría mal
     * colocada.
     */
    private void transitionTo(MitigationState target, Long actorId) {
        if (!state.canTransitionTo(target)) {
            throw new MitigationConflictException(
                    "Invalid state transition: " + state + " → " + target);
        }
        MitigationState previous = this.state;
        this.state = target;
        log(previous, target, actorId);
    }

    private void log(MitigationState from, MitigationState to, Long actorId) {
        history.add(MitigationActionTransition.builder()
                .organizationId(organizationId)
                .action(this)
                .fromState(from)
                .toState(to)
                .actorId(actorId)
                .occurredAt(LocalDateTime.now())
                .build());
    }
}
