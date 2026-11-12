package com.emsafe.occupationalhealth.domain.model;

/**
 * La acción ya no está en el estado que la operación requiere: aprobar algo ya
 * decidido, o perder una carrera contra otra aprobación simultánea.
 *
 * Se resuelve como 409, el código que el informe §5.6.2 asigna a las
 * transiciones no permitidas y a los conflictos de versión.
 */
public class MitigationConflictException extends MitigationRuleException {
    public MitigationConflictException(String message) {
        super(message);
    }
}
