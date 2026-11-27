package com.emsafe.occupationalhealth.application.events.inbound;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;

/**
 * Personal Dosimetry abrió la jornada de un operario en un puesto. Es lo que
 * abre su turno: el SSO no tiene que abrirlo aparte en el mapa de planta.
 */
public record ExposureSessionOpened(
        EventEnvelope envelope,
        String sessionId,
        Long workerId,
        Long positionId) {
}
