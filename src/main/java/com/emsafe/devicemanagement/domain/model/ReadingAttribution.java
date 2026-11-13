package com.emsafe.devicemanagement.domain.model;

/** A quién se atribuye una lectura según la organización que la envía. */
public enum ReadingAttribution {
    /** La organización del mensaje es la dueña del dispositivo. */
    OWNER,
    /** El dispositivo está prestado al Edge de otra organización. */
    LENT,
    REJECTED
}
