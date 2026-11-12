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
    NOT_VIABLE,
    /**
     * Desplazada por una propuesta más nueva sobre el mismo operario (regla 5).
     *
     * No está en los seis estados de §5.6.1 del informe, que no contempla
     * ninguno para esto. Se añade porque la alternativa —reutilizar NOT_VIABLE—
     * afirma en un reporte de cumplimiento, que es registro legal a cinco años,
     * que no se encontró rotación viable. Eso no ocurrió: solo llegó una
     * sugerencia posterior.
     */
    SUPERSEDED;

    /** Acciones que aún esperan algo: son las que ocupan la bandeja del SSO. */
    public static final List<MitigationState> OPEN = List.of(PROPOSED, APPROVED);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean isTerminal() {
        return !isOpen();
    }

    /**
     * Si el estado representa una medida que se llegó a tomar, y por tanto es
     * evidencia para un reporte de cumplimiento.
     *
     * SUPERSEDED queda fuera: una propuesta desplazada no es una medida tomada
     * ni una constancia de que no hubiera alternativa. Sigue en la bitácora del
     * agregado, pero no entra en el reporte.
     */
    public boolean isReportableEvidence() {
        return this != SUPERSEDED;
    }

    /**
     * Transiciones legales. A diferencia de WorkOrderStatus, quedarse en el
     * mismo estado NO es válido: aprobar dos veces la misma acción es un error
     * que debe devolver 409, no un no-op silencioso.
     *
     * Una propuesta ya aprobada todavía puede quedar desplazada: entre aprobar
     * y confirmar la reasignación puede llegar una sugerencia más urgente.
     */
    public boolean canTransitionTo(MitigationState target) {
        return switch (this) {
            case PROPOSED -> target == APPROVED || target == REJECTED
                    || target == NOT_VIABLE || target == SUPERSEDED;
            case APPROVED -> target == APPLIED || target == FAILED || target == SUPERSEDED;
            case APPLIED, FAILED, REJECTED, NOT_VIABLE, SUPERSEDED -> false;
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
