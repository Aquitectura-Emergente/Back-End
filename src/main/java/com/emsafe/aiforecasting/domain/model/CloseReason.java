package com.emsafe.aiforecasting.domain.model;

/** Motivo de cierre de una evaluación. */
public enum CloseReason {
    /** Un nuevo disparo para la misma sesión la reemplazó. */
    SUPERSEDED,
    /** La sesión de exposición terminó. */
    SESSION_ENDED
}
