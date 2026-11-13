package com.emsafe.devicemanagement.domain.model;

public enum PlugAction {
    ON,
    OFF;

    public PlugPower expectedPower() {
        return this == ON ? PlugPower.ON : PlugPower.OFF;
    }
}
