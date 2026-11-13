package com.emsafe.devicemanagement.application.ports;

import com.emsafe.devicemanagement.domain.model.PlugCommand;

/** Envía la orden al Edge hogar. */
public interface PlugCommandPublisher {
    /** @throws PublicationFailedException si no se pudo publicar */
    void publish(PlugCommand command);
}
