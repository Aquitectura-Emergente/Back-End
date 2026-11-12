package com.emsafe.occupationalhealth.domain.model;

/**
 * Estado del turno del operario.
 *
 * Solo puede haber un turno ACTIVE por operario y organización a la vez; la
 * base lo garantiza con un índice sobre una columna generada (ver V13), porque
 * MySQL no tiene UNIQUE parcial.
 */
public enum ShiftState {
    ACTIVE,
    CLOSED
}
