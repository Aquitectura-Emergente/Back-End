package com.emsafe.fieldservice.domain.model;

/** Tipo de servicio de una orden de trabajo. */
public enum WorkOrderType {
    INSTALLATION,
    MAINTENANCE,
    COLLECTION;

    private static final String NO_FILTER = "all";

    /** Traduce el texto de la API a un tipo; lanza si no corresponde a ninguno. */
    public static WorkOrderType from(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new WorkOrderRuleException("Invalid type: " + raw);
        }
    }

    /** Como {@link #from}, pero null cuando el parámetro significa "sin filtro". */
    public static WorkOrderType fromFilter(String raw) {
        if (raw == null || raw.isBlank() || NO_FILTER.equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return from(raw);
    }

    /** Nombre para mostrar: INSTALLATION -> "Installation". */
    public String displayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}
