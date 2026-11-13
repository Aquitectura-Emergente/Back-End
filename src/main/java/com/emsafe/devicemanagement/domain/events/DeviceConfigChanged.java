package com.emsafe.devicemanagement.domain.events;

import com.emsafe.devicemanagement.domain.model.ConfigDefinedBy;

import java.math.BigDecimal;
import java.time.Instant;

public record DeviceConfigChanged(String deviceId, Long organizationId, int configVersion,
                                  BigDecimal alertThresholdUT, ConfigDefinedBy definedBy,
                                  long samplingIntervalMs, long inactivityTimeoutS,
                                  Instant updatedAt) implements DeviceDomainEvent {
    @Override
    public Instant occurredAt() {
        return updatedAt;
    }
}
