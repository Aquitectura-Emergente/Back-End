package com.emsafe.devicemanagement.domain.model;

/** Destino que tuvo una lectura cruda después de guardarse. */
public enum ReadingRouting {
    /** Entregada a una jornada de Personal Dosimetry. */
    ASSIGNED_TO_SESSION,
    /** Dosímetro sin jornada que cubra su hora de captura: la lectura queda guardada. */
    UNASSIGNED,
    /** Registrada en el flujo del hogar. */
    HOME_RECORDED,
    /** El contexto destino la rechazó; el motivo queda en la lectura. */
    REJECTED_BY_TARGET
}
