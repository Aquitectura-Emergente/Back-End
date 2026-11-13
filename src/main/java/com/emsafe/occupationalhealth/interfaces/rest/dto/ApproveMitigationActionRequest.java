package com.emsafe.occupationalhealth.interfaces.rest.dto;

/**
 * Aprobación de una medida. El motivo es opcional: aprobar no exige
 * justificarse, al contrario que rechazar.
 *
 * toPositionId y toZoneId solo hacen falta cuando la propuesta no trae destino,
 * que es el caso de las acciones abiertas por una excedencia: el hecho informa
 * de que hay que mover al operario, y a dónde lo decide el SSO al aprobar. En
 * una rotación sugerida por el agente se ignoran, porque el destino es el
 * intercambio ya propuesto.
 *
 * El informe no cubre este caso — ver MitigationAction.addAffectedWorkerPendingTarget.
 */
public record ApproveMitigationActionRequest(
        String reason,
        Long toPositionId,
        Long toZoneId
) {
}
