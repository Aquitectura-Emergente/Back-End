package com.emsafe.aiforecasting.domain.ports;

import java.util.Map;

/** Nombres de los puestos de una organización, que son de Plant Configuration. */
public interface PositionCatalogPort {

    /**
     * {@code positionId → nombre}. Nunca falla: si el catálogo no responde devuelve un mapa vacío,
     * porque el nombre es solo decorativo.
     */
    Map<Long, String> positionNames(Long organizationId);
}
