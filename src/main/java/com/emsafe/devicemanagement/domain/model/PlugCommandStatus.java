package com.emsafe.devicemanagement.domain.model;

public enum PlugCommandStatus {
    /** Publicada o por publicar, todavía sin confirmación del Edge. */
    PENDING,
    /** El Edge informó el estado pedido. */
    CONFIRMED,
    /** El Edge respondió a esta orden pero el relé no cambió. */
    NOT_APPLIED,
    /** No se pudo publicar en el broker. */
    SEND_FAILED
}
