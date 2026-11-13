package com.emsafe.devicemanagement.application.ports;

/** No se pudo entregar un mensaje a los Edges; el mensaje dice por qué. */
public class PublicationFailedException extends RuntimeException {
    public PublicationFailedException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
