package com.emsafe.aiforecasting.application;

/**
 * Quién hace la petición, en los términos de este contexto.
 *
 * @param administrator  rol ADMIN
 * @param organizationId organización que el actor representa; en {@code develop} es el id de
 *                       una cuenta CLIENT con clientType=company (misma convención que Personal
 *                       Dosimetry). {@code null} si no representa a ninguna.
 */
public record ForecastingActor(Long userId, boolean administrator, Long organizationId) {
}
