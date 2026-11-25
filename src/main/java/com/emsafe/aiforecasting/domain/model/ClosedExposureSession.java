package com.emsafe.aiforecasting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Jornada ya consolidada por Personal Dosimetry, en los términos de este contexto. Sus cifras
 * están congeladas (índice final en uT·s) y las banderas de advertencia y excedencia son las que
 * calculó Personal Dosimetry: este contexto no las reinterpreta.
 *
 * @param positionId puesto vigente al cerrar la jornada
 * @param finalIndex índice acumulado final en uT·s
 * @param limitIndex límite de la jornada en uT·s
 */
public record ClosedExposureSession(String sessionId,
                                    Long workerId,
                                    Long positionId,
                                    BigDecimal finalIndex,
                                    BigDecimal limitIndex,
                                    boolean warningRaised,
                                    boolean limitExceeded,
                                    Instant startedAt,
                                    Instant endedAt,
                                    boolean coverageComplete) {
}
