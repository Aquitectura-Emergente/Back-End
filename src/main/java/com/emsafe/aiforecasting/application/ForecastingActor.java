package com.emsafe.aiforecasting.application;

/**
 * Quién hace la petición, en los términos de este contexto.
 *
 * @param administrator  rol ADMIN
 * @param organizationId organización que el actor representa: el {@code users.id} de la cuenta
 *                       CLIENT company (para esa misma cuenta o para su SSO_RESPONSIBLE).
 *                       {@code null} si no representa a ninguna.
 */
public record ForecastingActor(Long userId, boolean administrator, Long organizationId) {
}
