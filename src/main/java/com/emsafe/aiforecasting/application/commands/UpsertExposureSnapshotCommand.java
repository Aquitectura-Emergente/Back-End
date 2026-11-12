package com.emsafe.aiforecasting.application.commands;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Inbox {@code exposure-snapshot}: alta o actualización del snapshot de una sesión abierta.
 * {@code zoneId}, {@code positionId} y {@code plannedShiftEnd} son opcionales: si se omiten
 * se conservan los valores ya conocidos.
 */
public record UpsertExposureSnapshotCommand(String eventId,
                                            String correlationId,
                                            Long organizationId,
                                            Long workerId,
                                            String sessionId,
                                            String zoneId,
                                            Long positionId,
                                            BigDecimal accumulatedIndex,
                                            BigDecimal limitIndex,
                                            Instant sessionStartedAt,
                                            Instant plannedShiftEnd,
                                            Instant capturedAt) {
}
