package com.emsafe.occupationalhealth.domain.model;

/**
 * Qué originó la medida (informe §5.6.1).
 *
 * El origen decide si la acción nace ya decidida: una sugerencia del agente o
 * una excedencia solo pueden crear propuestas, porque toda medida sobre la
 * operación exige confirmación humana (regla 1).
 */
public enum MitigationOrigin {
    /** Propuesta por AI Forecasting & Analytics. */
    AI_SUGGESTION,
    /** Abierta al confirmarse una excedencia en Personal Dosimetry. */
    LIMIT_EXCEEDED,
    /** Registrada directamente por el Responsable de SSO. */
    MANUAL;

    /**
     * Solo lo manual nace con decisión tomada: el SSO ya decidió al registrarlo.
     * Los otros dos orígenes entran en PROPOSED y esperan su aprobación.
     */
    public boolean isAlreadyDecided() {
        return this == MANUAL;
    }

    /**
     * Los orígenes automáticos deben traer el identificador del hecho que los
     * provocó, que es lo que permite descartar mensajes duplicados.
     */
    public boolean requiresSourceEvent() {
        return this != MANUAL;
    }
}
