package com.emsafe.aiforecasting.application.commands;

import com.emsafe.aiforecasting.domain.model.ExposureMethod;

import java.math.BigDecimal;
import java.time.Instant;

/** Inbox {@code exposure-warning-raised}: evento de Personal Dosimetry que dispara una evaluación. */
public record ExposureWarningRaisedCommand(String eventId,
                                           String correlationId,
                                           Long organizationId,
                                           String sessionId,
                                           Long workerId,
                                           BigDecimal accumulatedIndex,
                                           BigDecimal limitIndex,
                                           Long limitVersionId,
                                           Instant capturedAt,
                                           String methodId,
                                           String unit) {

    public static final String SUPPORTED_METHOD = ExposureMethod.MAGNETIC_TIME_INDEX_V1;
    public static final String SUPPORTED_UNIT = ExposureMethod.MICROTESLA_SECONDS;
}
