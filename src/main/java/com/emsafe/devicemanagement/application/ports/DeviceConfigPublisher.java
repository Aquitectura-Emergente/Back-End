package com.emsafe.devicemanagement.application.ports;

import com.emsafe.devicemanagement.domain.events.DeviceConfigChanged;

/** Entrega la configuración del dosímetro a los Edges. */
public interface DeviceConfigPublisher {
    /** @throws PublicationFailedException si no se pudo publicar y hay que reintentar */
    void publish(DeviceConfigChanged config);
}
