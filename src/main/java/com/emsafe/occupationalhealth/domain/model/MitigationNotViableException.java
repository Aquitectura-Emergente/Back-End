package com.emsafe.occupationalhealth.domain.model;

/**
 * La medida no puede aplicarse por una razón de seguridad: la zona destino es
 * de riesgo, o a quien se mueve no le queda margen frente a su límite.
 *
 * Se resuelve como 422, el código que el informe §5.6.2 asigna a zonas de
 * riesgo, períodos inválidos y márgenes insuficientes. Se distingue del 409
 * porque aquí la petición era coherente: lo que falla es la realidad de la
 * planta, no el estado de la acción.
 */
public class MitigationNotViableException extends MitigationUnprocessableException {
    public MitigationNotViableException(String message) {
        super(message);
    }
}
