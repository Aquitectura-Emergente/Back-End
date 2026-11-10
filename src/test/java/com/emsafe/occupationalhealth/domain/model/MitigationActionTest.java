package com.emsafe.occupationalhealth.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reglas del agregado MitigationAction (informe §5.6.1).
 *
 * Son tests del dominio puro: sin Spring, sin base de datos. Si una de estas
 * reglas se puede saltar, está mal colocada.
 */
class MitigationActionTest {

    private static final Long ORG = 7L;

    private static MitigationTrigger suggestion(String eventId) {
        return MitigationTrigger.fromEvent(eventId, TriggerKind.ROTATION_SUGGESTED,
                LocalDateTime.now());
    }

    private static DoseSnapshot dose(String value, String limit) {
        return DoseSnapshot.of(new BigDecimal(value), "uT.h", "TEST", new BigDecimal(limit));
    }

    // ── Regla 1: ninguna medida se aplica sin decisión humana ─────────────────

    @Test
    @DisplayName("una sugerencia del agente nace PROPOSED, nunca decidida")
    void aiSuggestionStartsProposed() {
        MitigationAction action = MitigationAction.propose(ORG, MitigationType.ROTATION,
                MitigationOrigin.AI_SUGGESTION, suggestion("evt-1"), "proyección de sobreexposición");

        assertEquals(MitigationState.PROPOSED, action.getState());
        assertNull(action.getDecision(), "no puede traer decisión: nadie la ha tomado");
    }

    @Test
    @DisplayName("una excedencia confirmada tampoco crea una medida ya aprobada")
    void limitExceededStartsProposed() {
        MitigationAction action = MitigationAction.propose(ORG, MitigationType.RELOCATION,
                MitigationOrigin.LIMIT_EXCEEDED,
                MitigationTrigger.fromEvent("evt-2", TriggerKind.OCCUPATIONAL_LIMIT_EXCEEDED, null),
                "límite superado");

        assertEquals(MitigationState.PROPOSED, action.getState());
    }

    @Test
    @DisplayName("un origen automático sin evento de respaldo se rechaza")
    void automaticOriginRequiresSourceEvent() {
        MitigationRuleException ex = assertThrows(MitigationRuleException.class,
                () -> MitigationAction.propose(ORG, MitigationType.ROTATION,
                        MitigationOrigin.AI_SUGGESTION, MitigationTrigger.manual(), null));

        assertTrue(ex.getMessage().contains("requires a source event"));
    }

    @Test
    @DisplayName("aplicar sin pasar por una decisión es imposible")
    void cannotApplyWithoutDecision() {
        MitigationAction action = MitigationAction.propose(ORG, MitigationType.RELOCATION,
                MitigationOrigin.MANUAL, MitigationTrigger.manual(), null);

        // PROPOSED → APPLIED no es una transición legal: hay que aprobar primero.
        assertThrows(MitigationConflictException.class, () -> action.markApplied("forzado"));
    }

    // ── Estado: las transiciones están cerradas ──────────────────────────────

    @Test
    @DisplayName("aprobar dos veces la misma acción es un conflicto, no un no-op")
    void doubleApprovalConflicts() {
        MitigationAction action = approvedRelocation();

        MitigationConflictException ex = assertThrows(MitigationConflictException.class,
                () -> action.approve(Decision.approvedBy(99L, null)));
        assertTrue(ex.getMessage().contains("APPROVED"));
    }

    @Test
    @DisplayName("una acción rechazada ya no admite cambios")
    void rejectedIsTerminal() {
        MitigationAction action = MitigationAction.propose(ORG, MitigationType.RELOCATION,
                MitigationOrigin.MANUAL, MitigationTrigger.manual(), null);
        action.reject(Decision.rejectedBy(1L, "no hay reemplazo disponible"));

        assertEquals(MitigationState.REJECTED, action.getState());
        assertTrue(action.getState().isTerminal());
        assertThrows(MitigationConflictException.class,
                () -> action.approve(Decision.approvedBy(1L, null)));
    }

    // ── Regla 2: el historial lo escribe el agregado, no el servicio ──────────

    @Test
    @DisplayName("cada transición deja una entrada en la bitácora")
    void everyTransitionIsLogged() {
        MitigationAction action = approvedRelocation();
        action.markApplied("aplicada");

        assertEquals(3, action.getHistory().size(), "PROPOSED, APPROVED y APPLIED");
        assertNull(action.getHistory().get(0).getFromState());
        assertEquals(MitigationState.PROPOSED, action.getHistory().get(0).getToState());
        assertEquals(MitigationState.APPLIED, action.getHistory().get(2).getToState());
        assertEquals(42L, action.getHistory().get(1).getActorId(), "quién aprobó");
    }

    // ── Regla 5: una sola medida viva por operario ───────────────────────────

    @Test
    @DisplayName("rotación y reubicación compiten por el operario; reprogramar no")
    void onlyMovingActionsCompete() {
        assertTrue(proposalOf(MitigationType.ROTATION).competesForWorker());
        assertTrue(proposalOf(MitigationType.RELOCATION).competesForWorker());
        assertFalse(proposalOf(MitigationType.RESCHEDULING).competesForWorker());
    }

    @Test
    @DisplayName("una propuesta desplazada se cierra y apunta a la que la reemplaza")
    void supersededProposalIsClosed() {
        MitigationAction old = proposalOf(MitigationType.ROTATION);
        MitigationAction replacement = proposalOf(MitigationType.RELOCATION);
        replacement.setId(123L);

        old.supersedeBy(replacement);

        assertEquals(MitigationState.NOT_VIABLE, old.getState());
        assertEquals(123L, old.getSupersededById());
        assertTrue(old.getOutcome().contains("Superseded"));
    }

    @Test
    @DisplayName("una acción ya cerrada no se puede desplazar")
    void cannotSupersedeAClosedAction() {
        MitigationAction old = proposalOf(MitigationType.ROTATION);
        old.declareNotViable("sin candidato");

        assertThrows(MitigationRuleException.class,
                () -> old.supersedeBy(proposalOf(MitigationType.RELOCATION)));
    }

    // ── Tope de operarios por tipo ───────────────────────────────────────────

    @Test
    @DisplayName("una rotación admite dos operarios y no un tercero")
    void rotationHoldsTwoWorkers() {
        MitigationAction rotation = proposalOf(MitigationType.ROTATION);
        rotation.addAffectedWorker(1L, 10L, 20L, 200L, dose("100", "800"));
        rotation.addAffectedWorker(2L, 20L, 10L, 100L, dose("50", "800"));

        assertEquals(2, rotation.getAffectedWorkers().size());
        MitigationRuleException ex = assertThrows(MitigationRuleException.class,
                () -> rotation.addAffectedWorker(3L, 30L, 40L, 300L, dose("10", "800")));
        assertTrue(ex.getMessage().contains("at most 2"));
    }

    @Test
    @DisplayName("una reubicación exige puesto y zona destino")
    void relocationRequiresTarget() {
        MitigationAction relocation = proposalOf(MitigationType.RELOCATION);

        assertThrows(MitigationRuleException.class,
                () -> relocation.addAffectedWorker(1L, 10L, null, null, dose("100", "800")));
    }

    @Test
    @DisplayName("una reprogramación no exige destino: no mueve a nadie")
    void reschedulingNeedsNoTarget() {
        MitigationAction rescheduling = proposalOf(MitigationType.RESCHEDULING);
        rescheduling.addAffectedWorker(1L, 10L, null, null, dose("100", "800"));

        assertEquals(1, rescheduling.getAffectedWorkers().size());
    }

    // ── Regla 4: revalidar el margen al aprobar ──────────────────────────────

    @Test
    @DisplayName("revalidar actualiza la foto y detecta al que ya no tiene margen")
    void refreshDetectsExhaustedMargin() {
        MitigationAction rotation = proposalOf(MitigationType.ROTATION);
        rotation.addAffectedWorker(1L, 10L, 20L, 200L, dose("100", "800"));
        rotation.addAffectedWorker(2L, 20L, 10L, 100L, dose("50", "800"));

        Map<Long, DoseSnapshot> now = Map.of(
                1L, dose("200", "800"),
                2L, dose("900", "800"));   // el reemplazo se pasó del límite

        assertFalse(rotation.refreshDosesAndCheckMargin(now::get));
        // compareTo y no equals: BigDecimal.equals distingue 200 de 200.0000.
        assertEquals(0, new BigDecimal("200").compareTo(
                        rotation.getAffectedWorkers().get(0).getDoseAtDecision().getValue()),
                "la foto se actualiza con la cifra de ahora");
    }

    @Test
    @DisplayName("si todos conservan margen, la revalidación pasa")
    void refreshPassesWhenMarginRemains() {
        MitigationAction rotation = proposalOf(MitigationType.ROTATION);
        rotation.addAffectedWorker(1L, 10L, 20L, 200L, dose("100", "800"));

        assertTrue(rotation.refreshDosesAndCheckMargin(id -> dose("400", "800")));
    }

    // ── Regla 10: prioridad por escalamiento ─────────────────────────────────

    @Test
    @DisplayName("un escalamiento sube la prioridad de una propuesta viva")
    void escalationRaisesPriority() {
        MitigationAction action = proposalOf(MitigationType.ROTATION);
        assertEquals(MitigationPriority.NORMAL, action.getPriority());

        action.raisePriority();
        assertEquals(MitigationPriority.HIGH, action.getPriority());
    }

    @Test
    @DisplayName("escalar una acción ya cerrada no cambia nada")
    void escalationIgnoresClosedActions() {
        MitigationAction action = proposalOf(MitigationType.ROTATION);
        action.declareNotViable("sin candidato");

        action.raisePriority();
        assertEquals(MitigationPriority.NORMAL, action.getPriority());
    }

    // ── Reprogramación derivada ──────────────────────────────────────────────

    @Test
    @DisplayName("una rotación no viable origina una reprogramación con los mismos operarios")
    void notViableRotationSpawnsRescheduling() {
        MitigationAction rotation = proposalOf(MitigationType.ROTATION);
        rotation.setId(5L);
        rotation.addAffectedWorker(1L, 10L, 20L, 200L, dose("790", "800"));
        rotation.declareNotViable("el reemplazo no tiene margen");

        MitigationAction rescheduling = MitigationAction.proposeReschedulingFrom(rotation);

        assertEquals(MitigationType.RESCHEDULING, rescheduling.getType());
        assertEquals(MitigationState.PROPOSED, rescheduling.getState());
        assertEquals(java.util.List.of(1L), rescheduling.affectedWorkerIds());
        assertNull(rescheduling.getTrigger().getSourceEventId(),
                "no puede reusar el sourceEventId: rompería el UNIQUE que descarta duplicados");
    }

    // ── Ayudas ───────────────────────────────────────────────────────────────

    private static MitigationAction proposalOf(MitigationType type) {
        return MitigationAction.propose(ORG, type, MitigationOrigin.MANUAL,
                MitigationTrigger.manual(), "prueba");
    }

    private static MitigationAction approvedRelocation() {
        MitigationAction action = proposalOf(MitigationType.RELOCATION);
        action.addAffectedWorker(1L, 10L, 20L, 200L, dose("100", "800"));
        action.approve(Decision.approvedBy(42L, null));
        return action;
    }
}
