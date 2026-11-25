package com.emsafe.aiforecasting.domain.ports;

import com.emsafe.aiforecasting.domain.model.ClosedExposureSession;

import java.time.Instant;
import java.util.List;

/** Histórico de jornadas consolidadas, que es de Personal Dosimetry. */
public interface ClosedSessionHistoryPort {

    /** Jornadas cerradas de la organización con {@code endedAt} en {@code [from, to)}, en orden de cierre. */
    List<ClosedExposureSession> closedSessions(Long organizationId, Instant from, Instant to);
}
