package com.emsafe.devicemanagement.domain.model;

/** Ciclo de vida físico del dispositivo, independiente de su conectividad. */
public enum DeviceLifecycleStatus {
    IN_STOCK,
    INSTALLED,
    RETIRED;

    /** Un dispositivo retirado no vuelve; el resto va y viene entre almacén e instalación. */
    public boolean canMoveTo(DeviceLifecycleStatus target) {
        return target != null && target != this && this != RETIRED;
    }
}
