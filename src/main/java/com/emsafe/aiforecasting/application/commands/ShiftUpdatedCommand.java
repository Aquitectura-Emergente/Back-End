package com.emsafe.aiforecasting.application.commands;

import java.time.Instant;

/**
 * Inbox {@code shift-updated}: cambio de zona, puesto o fin de turno planificado.
 * Los campos nulos se conservan.
 */
public record ShiftUpdatedCommand(String eventId,
                                  String correlationId,
                                  Long organizationId,
                                  Long workerId,
                                  String sessionId,
                                  String zoneId,
                                  Long positionId,
                                  Instant plannedShiftEnd) {
}
