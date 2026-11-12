package com.emsafe.occupationalhealth.domain.model;

/** Naturaleza de la medida preventiva (informe §5.6.0, lenguaje ubicuo). */
public enum MitigationType {
    /** Intercambio de puestos entre dos operarios de la misma área. */
    ROTATION,
    /** Traslado de un operario a otra zona, sin intercambio. */
    RELOCATION,
    /** Postergación de la tarea cuando ninguna rotación es viable. */
    RESCHEDULING;

    /**
     * Una rotación mueve a dos personas; reubicar y reprogramar afectan a una.
     * El límite lo aplica MitigationAction al añadir operarios.
     */
    public int maxAffectedWorkers() {
        return this == ROTATION ? 2 : 1;
    }

    /**
     * Reprogramar no mueve a nadie de sitio, así que no exige zona destino ni
     * revalidación de margen al aprobar.
     */
    public boolean movesWorker() {
        return this != RESCHEDULING;
    }

    public static MitigationType from(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new MitigationRuleException("Invalid type: " + raw);
        }
    }
}
