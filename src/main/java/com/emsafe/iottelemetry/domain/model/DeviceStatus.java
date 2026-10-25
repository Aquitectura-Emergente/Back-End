package com.emsafe.iottelemetry.domain.model;

/**
 * Estados del ciclo de vida de un dispositivo.
 *
 * Se modelan como constantes y no como enum a propósito: la columna
 * `devices.status` es VARCHAR y guarda valores con guion ("requires-maintenance"),
 * que no son nombres válidos de constante de enum. Convertirla exigiría un
 * AttributeConverter y tocar el contrato con la base, y el esquema se queda como
 * está. Lo que sí se gana es que los literales dejan de estar sueltos por tres
 * bounded contexts.
 */
public final class DeviceStatus {

    /** Instalado y reportando con normalidad. */
    public static final String ACTIVE = "active";

    /** Detectado por el edge pero todavía sin cliente asignado. */
    public static final String UNREGISTERED = "unregistered";

    /** Marcado para revisión; un mantenimiento lo pasará a IN_MAINTENANCE. */
    public static final String REQUIRES_MAINTENANCE = "requires-maintenance";

    /** Con un mantenimiento en curso. */
    public static final String IN_MAINTENANCE = "in-maintenance";

    private DeviceStatus() {
    }

    public static boolean isUnregistered(String status) {
        return UNREGISTERED.equals(status);
    }
}
