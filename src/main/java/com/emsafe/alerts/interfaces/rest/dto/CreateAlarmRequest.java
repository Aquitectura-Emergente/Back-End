package com.emsafe.alerts.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateAlarmRequest(
        @NotBlank String type,
        String icon,
        @NotBlank String title,
        String description,
        String relativeTime,

        /** all | specific */
        String recipientType,

        /** Selected client ids when recipientType = specific. */
        List<Long> clientIds,

        String sensor,

        // ─── Alerta de operario (V23) ─────────────────────────────────────────
        // Los tres son opcionales: sin ellos se crea una alarma de cliente, que
        // es lo único que este contexto sabía hacer hasta V22. Con `workerId`
        // pasa a ser una alerta de operario, acusable y escalable.

        /** Empresa del operario. Exigido por el sobre del evento de evidencia. */
        Long organizationId,

        /** Operario al que concierne. Su presencia hace a la alerta "de operario". */
        Long workerId,

        /** Jornada de exposición de la que proviene, si proviene de una. */
        String sessionId
) {}
