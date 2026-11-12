package com.emsafe.plantconfiguration.domain.model;

import com.emsafe.shared.exception.BadRequestException;

/**
 * Regla de configuración de planta incumplida.
 *
 * <p>Extiende {@link BadRequestException} a propósito: el estilo elegido para
 * este contexto es el del CLAUDE.md —excepciones de {@code shared} resueltas de
 * forma centralizada en {@code GlobalExceptionHandler}—, no el
 * {@code @RestControllerAdvice} por contexto que usan {@code aiforecasting} y
 * {@code personaldosimetry}. El contrato HTTP resultante es 400.
 */
public class PlantConfigurationRuleException extends BadRequestException {

    public PlantConfigurationRuleException(String message) {
        super(message);
    }
}
