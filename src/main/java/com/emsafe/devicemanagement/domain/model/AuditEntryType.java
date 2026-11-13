package com.emsafe.devicemanagement.domain.model;

public enum AuditEntryType {
    REGISTERED,
    CONFIG_CHANGED,
    LINKED,
    CONNECTIVITY_CHANGED,
    LIFECYCLE_CHANGED,
    ZONE_CHANGED,
    PLUG_COMMAND,
    PLUG_STATE,
    DEVICE_TYPE_MISMATCH,
    DEVICE_LENT
}
