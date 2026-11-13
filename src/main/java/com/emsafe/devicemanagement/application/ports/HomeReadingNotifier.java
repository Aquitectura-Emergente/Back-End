package com.emsafe.devicemanagement.application.ports;

import com.emsafe.devicemanagement.application.ports.HomeReadingGateway.RecordedHomeReading;

/** Avisa a la app y al portal de una lectura del hogar (la app muestra la ventana de decisión). */
public interface HomeReadingNotifier {
    void notify(RecordedHomeReading reading, boolean unregisteredDevice);
}
