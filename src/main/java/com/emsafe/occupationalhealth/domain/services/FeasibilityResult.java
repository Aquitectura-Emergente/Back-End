package com.emsafe.occupationalhealth.domain.services;

/**
 * Resultado de evaluar si una medida puede aplicarse (informe §5.6.1).
 *
 * Lleva el motivo pegado al veredicto en vez de devolverlo como una tupla
 * suelta — el mismo criterio con el que AlertLevel del edge lleva su mensaje.
 */
public record FeasibilityResult(boolean viable, String reason) {

    /** Se llama feasible() y no viable() porque el accesor del record ya ocupa ese nombre. */
    public static FeasibilityResult feasible() {
        return new FeasibilityResult(true, null);
    }

    public static FeasibilityResult notViable(String reason) {
        return new FeasibilityResult(false, reason);
    }

    public boolean isNotViable() {
        return !viable;
    }
}
