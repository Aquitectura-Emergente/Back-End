package com.emsafe.devicemanagement.application.ports;

import com.emsafe.devicemanagement.domain.model.PlugCommand;

/** Envía la orden al Edge hogar. */
public interface PlugCommandPublisher {
    /** @return false si no se pudo publicar */
    boolean publish(PlugCommand command);
}
