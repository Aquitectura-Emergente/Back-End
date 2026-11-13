package com.emsafe.devicemanagement.domain.model;

/** De dónde sale la hora de captura: el reloj del dispositivo o la recepción en el Edge. */
public enum CapturedAtSource {
    DEVICE_CLOCK,
    EDGE_RECEIVED
}
