package com.emsafe.occupationalhealth.domain.model;

/**
 * A qué se refiere un reporte de cumplimiento (informe §5.6.1).
 *
 * El alcance decide qué puede contener: un reporte por zona solo lleva
 * promedios y máximos agregados, sin identificadores de operarios, conforme a
 * RF32 y RNF11. La regla la aplica ComplianceReport al construirse.
 */
public enum ReportScope {
    /** Un trabajador concreto: lleva su exposición, excedencias y acciones. */
    WORKER,
    /** Zonas de planta: solo agregados, sin poder señalar a nadie. */
    PLANT_ZONE;

    /** Si este alcance admite datos que identifiquen a un operario. */
    public boolean allowsIndividualData() {
        return this == WORKER;
    }

    public static ReportScope from(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new MitigationRuleException("Invalid scope: " + raw);
        }
    }
}
