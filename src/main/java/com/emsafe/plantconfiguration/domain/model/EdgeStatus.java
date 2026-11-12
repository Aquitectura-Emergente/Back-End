package com.emsafe.plantconfiguration.domain.model;

/**
 * Estado operativo de un edge declarado por el SSO.
 *
 * <p>Constantes de texto, no un enum, por coherencia con {@code DeviceStatus} y
 * {@code UserStatus} del resto del backend: la columna es {@code VARCHAR} con un
 * {@code CHECK} en la migración.
 *
 * <p>Ojo con el lenguaje ubicuo: {@code ACTIVE} aquí significa *declarado y en
 * servicio por el SSO*, que no es lo mismo que el {@code ACTIVE} de
 * {@code Device} (sensor instalado y reportando) ni el de {@code AppUser}
 * (cuenta habilitada). No los unifiques.
 */
public final class EdgeStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    private EdgeStatus() {
    }

    public static boolean isValid(String value) {
        return ACTIVE.equals(value) || INACTIVE.equals(value);
    }
}
