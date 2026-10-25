package com.emsafe.fieldservice.domain.model;

import java.util.List;

/**
 * Ciclo de vida de una orden de trabajo.
 *
 * El enum es dueño de sus propias reglas: qué transiciones son legales, qué
 * estados siguen vivos y cómo se traduce el texto que llega por la API. Antes
 * esto vivía repartido en WorkOrderService (validateTransition, ACTIVE_STATUSES,
 * parseStatus, parseStatusStrict, parseActiveStatus), donde cualquier flujo
 * nuevo podía saltárselo sin que nada lo impidiera.
 */
public enum WorkOrderStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    /**
     * Estados que siguen vivos. Las listas de admin y técnico solo muestran
     * estos: Completed y Cancelled existen únicamente en el Historial.
     */
    public static final List<WorkOrderStatus> ACTIVE = List.of(PENDING, IN_PROGRESS);

    /** El filtro "all" (en cualquier caja) significa "sin filtro". */
    private static final String NO_FILTER = "all";

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    /** Un estado terminal ya no admite ninguna transición. */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    /**
     * Transiciones legales: PENDING→IN_PROGRESS, IN_PROGRESS→COMPLETED y
     * desde cualquier estado vivo→CANCELLED. Quedarse en el mismo estado es
     * siempre válido (reenviar el mismo PATCH no debe fallar).
     */
    public boolean canTransitionTo(WorkOrderStatus target) {
        if (this == target) return true;
        return switch (this) {
            case PENDING -> target == IN_PROGRESS || target == CANCELLED;
            case IN_PROGRESS -> target == COMPLETED || target == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    /**
     * Traduce el texto de la API a un estado. Acepta tanto "in-progress" como
     * "IN_PROGRESS". Lanza si el valor no corresponde a ningún estado.
     */
    public static WorkOrderStatus from(String raw) {
        try {
            return valueOf(raw.trim().toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new WorkOrderRuleException("Invalid status: " + raw);
        }
    }

    /**
     * Igual que {@link #from}, pero devuelve null cuando el parámetro significa
     * "sin filtro" (ausente, vacío o "all"). Un valor presente pero inexistente
     * sigue siendo un error.
     */
    public static WorkOrderStatus fromFilter(String raw) {
        if (raw == null || raw.isBlank() || NO_FILTER.equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return from(raw);
    }

    /**
     * Estados a consultar en las listas activas: el único estado activo pedido,
     * o ambos cuando no se filtró o se pidió uno que no está vivo.
     *
     * Plegar el filtro dentro del IN evita enlazar un enum null como parámetro
     * escalar, que Hibernate no sabe tipar.
     */
    public static List<WorkOrderStatus> activeFilter(String raw) {
        WorkOrderStatus parsed = fromFilter(raw);
        return (parsed != null && parsed.isActive()) ? List.of(parsed) : ACTIVE;
    }
}
