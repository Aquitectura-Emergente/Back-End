package com.emsafe.occupationalhealth.domain.model;

/** Tipo de hecho que originó una medida, dentro de MitigationTrigger. */
public enum TriggerKind {
    /** Personal Dosimetry confirmó que el acumulado superó el límite del puesto. */
    OCCUPATIONAL_LIMIT_EXCEEDED,
    /** AI Forecasting proyectó una sobreexposición y propuso una rotación. */
    ROTATION_SUGGESTED,
    /** El Responsable de SSO actuó por su cuenta, sin hecho previo registrado. */
    MANUAL_DECISION
}
