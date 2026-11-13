package com.emsafe.plantconfiguration.interfaces.rest.dto;

import java.time.LocalDateTime;

/**
 * Zona tal como la ve el portal del SSO.
 *
 * <p>Incluye el serial del edge y los recuentos porque son justo lo que hace
 * falta para pintar la lista de zonas sin una petición por fila: una zona sin
 * edge o sin sensores es un hueco de configuración que el SSO debe ver de un
 * vistazo.
 */
public record PlantZoneDto(
        Long id,
        Long organizationId,
        String name,
        String description,
        LocalDateTime createdAt,
        String edgeSerialNumber,
        String edgeStatus,
        long deviceCount,
        long jobPositionCount
) {
}
