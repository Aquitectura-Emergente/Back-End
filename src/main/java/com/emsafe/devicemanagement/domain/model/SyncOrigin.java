package com.emsafe.devicemanagement.domain.model;

/** REALTIME llega al momento; DEFERRED viene del buffer offline del dispositivo. */
public enum SyncOrigin {
    REALTIME,
    DEFERRED
}
