package com.emsafe.devicemanagement.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public final class DeviceManagementRequests {
    private DeviceManagementRequests() {
    }

    public record ChangeAlertThreshold(@NotNull BigDecimal alertThresholdUT) {
    }

    public record ChangeOperationalConfig(Long samplingIntervalMs, Long inactivityTimeoutS) {
    }

    public record ChangeLifecycle(@NotBlank String status) {
    }

    public record AssignOrganizationCode(@NotBlank String code, @NotNull Long organizationId) {
    }

    public record RegisterEdgeNode(@NotBlank String edgeNodeId, @NotNull Long organizationId,
                                   @NotBlank String segment) {
    }

    public record RequestPlugCommand(@NotBlank String action) {
    }
}
