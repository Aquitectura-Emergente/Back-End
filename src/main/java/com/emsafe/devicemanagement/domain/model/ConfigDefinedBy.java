package com.emsafe.devicemanagement.domain.model;

/** Quién definió el umbral vigente. Viaja tal cual en {@code definedBy}. */
public enum ConfigDefinedBy {
    SSO_RESPONSIBLE,
    /** Umbral por defecto al registrar el dispositivo. */
    SYSTEM_DEFAULT
}
