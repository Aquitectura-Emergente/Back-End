package com.emsafe.aiforecasting.domain.ports;

import java.util.Optional;

/** La jornada abierta de un operario, que es de Personal Dosimetry. */
public interface OpenSessionPort {

    /**
     * {@code sessionId} de la jornada {@code OPEN} vigente del operario en la organización, o vacío si
     * Personal Dosimetry no le conoce ninguna abierta (por ejemplo, una sesión que solo llegó por el
     * inbox REST de este contexto).
     */
    Optional<String> openSessionId(Long organizationId, Long workerId);
}
