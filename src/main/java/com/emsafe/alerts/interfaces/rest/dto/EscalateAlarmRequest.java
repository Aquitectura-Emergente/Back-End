package com.emsafe.alerts.interfaces.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Escalamiento de una alerta de operario al Responsable de SSO (informe §5.6.3).
 *
 * <p>{@code reason} es opcional: el informe lo lista entre los campos de
 * {@code AlertEscalated}, pero un escalamiento automático por falta de acuse no
 * tiene un motivo que escribir y no debe quedar bloqueado por eso.</p>
 */
public record EscalateAlarmRequest(
        @NotNull(message = "ssoResponsibleId is required") @Positive Long ssoResponsibleId,
        @Size(max = 500) String reason
) {}
