package com.emsafe.fieldservice.domain.model;

import com.emsafe.shared.exception.BadRequestException;

/**
 * Violación de una regla de negocio de las órdenes de trabajo: una transición
 * de estado ilegal, un estado o tipo que no existe.
 *
 * Extiende BadRequestException a propósito: el dominio expresa el problema en
 * su propio lenguaje, pero GlobalExceptionHandler la sigue resolviendo como
 * 400 porque @ExceptionHandler también captura las subclases. El contrato HTTP
 * no cambia.
 */
public class WorkOrderRuleException extends BadRequestException {

    public WorkOrderRuleException(String message) {
        super(message);
    }
}
