package com.emsafe.occupationalhealth.domain.model;

/**
 * La petición está bien formada pero no puede procesarse: zona de riesgo,
 * período inválido o margen insuficiente. Son los tres casos a los que el
 * informe §5.6.2 asigna un 422.
 *
 * Existe como padre para que el GlobalExceptionHandler registre un solo handler
 * y no uno por cada motivo.
 */
public class MitigationUnprocessableException extends MitigationRuleException {
    public MitigationUnprocessableException(String message) {
        super(message);
    }
}
