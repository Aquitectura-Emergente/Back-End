package com.emsafe.occupationalhealth.domain.model;

import java.util.List;

/**
 * Ciclo de vida de una medida de mitigación (informe §5.6.1).
 *
 * Como en WorkOrderStatus, el enum es dueño de sus transiciones: así un flujo
 * nuevo no puede llevar una acción a un estado imposible por olvidar un
 * validador externo.
 */
public enum MitigationState {
    /** Propuesta, a la espera de la decisión del Responsable de SSO. */
    PROPOSED,
    /** Decidida a favor; la reasignación de turnos todavía no se ha confirmado. */
    APPROVED,
    /** Reasignación confirmada. Estado terminal. */
    APPLIED,
    /** La reasignación no pudo aplicarse. Estado terminal. */
    FAILED,
    /** Decidida en contra, con motivo obligatorio. Estado terminal. */
    REJECTED,
    /** Sin candidato con margen: cierra la sugerencia y origina una reprogramación. */
    NOT_VIABLE;

    /** Acciones que aún esperan algo: son las que ocupan la bandeja del SSO. */
    public static final List<MitigationState> OPEN = List.of(PROPOSED, APPROVED);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean isTerminal() {
        return this == APPLIED || this == FAILED || this == REJECTED || this == NOT_VIABLE;
    }

    /**
     * Transiciones legales. A diferencia de WorkOrderStatus, quedarse en el
     * mismo estado NO es válido: aprobar dos veces la misma acción es un error
     * que debe devolver 409, no un no-op silencioso.
     */
    public boolean canTransitionTo(MitigationState target) {
        return switch (this) {
            case PROPOSED -> target == APPROVED || target == REJECTED || target == NOT_VIABLE;
            case APPROVED -> target == APPLIED || target == FAILED;
            case APPLIED, FAILED, REJECTED, NOT_VIABLE -> false;
        };
    }

    public static MitigationState from(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new MitigationRuleException("Invalid state: " + raw);
        }
    }

    /** Devuelve null cuando el parámetro significa "sin filtro". */
    public static MitigationState fromFilter(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return from(raw);
    }
}
