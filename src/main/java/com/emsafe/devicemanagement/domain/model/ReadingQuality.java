package com.emsafe.devicemanagement.domain.model;

public enum ReadingQuality {
    VALID,
    /** El sensor llegó a su tope: el campo fue de al menos el valor informado. */
    SATURATED;

    public boolean isLowerBound() {
        return this == SATURATED;
    }
}
