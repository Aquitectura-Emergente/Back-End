package com.emsafe.iam.domain.model;

/**
 * Estados de una cuenta.
 *
 * Ojo con el parecido: "active" aquí significa cuenta habilitada, y no tiene
 * nada que ver con {@code DeviceStatus.ACTIVE}, que describe un sensor
 * instalado y reportando. Son dos bounded contexts distintos usando la misma
 * palabra, que es justo el caso que el lenguaje ubicuo obliga a separar.
 */
public final class UserStatus {

    public static final String ACTIVE = "active";
    public static final String INACTIVE = "inactive";

    /** Auto-registro desde el móvil: no puede entrar hasta que un admin lo active. */
    public static final String PENDING = "pending";

    private UserStatus() {
    }
}
