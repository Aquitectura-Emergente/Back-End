package com.emsafe.devicemanagement.domain.model;

/** Tipo con el que se registró el dispositivo. El contrato MQTT usa los dos primeros. */
public enum DeviceType {
    PERSONAL_DOSIMETER,
    FIXED_SENSOR,
    SMART_PLUG
}
