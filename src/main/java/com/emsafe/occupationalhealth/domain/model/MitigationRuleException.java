package com.emsafe.occupationalhealth.domain.model;

import com.emsafe.shared.exception.BadRequestException;

/**
 * Una regla del contexto se ha violado.
 *
 * Extiende BadRequestException para que GlobalExceptionHandler la siga
 * resolviendo como 400 con el mismo mensaje, sin tocar el contrato HTTP —
 * el mismo criterio que WorkOrderRuleException en fieldservice.
 */
public class MitigationRuleException extends BadRequestException {
    public MitigationRuleException(String message) {
        super(message);
    }
}
