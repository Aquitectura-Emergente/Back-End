package com.emsafe.fieldservice.interfaces.rest.dto;

import java.util.List;

/**
 * Used by the technician to drive the work-order lifecycle: status transitions,
 * notes, work-order sensors, activity log, and — depending on the service type —
 * device creation (Installation), device status updates (Maintenance/Collection),
 * maintenance actions and evidence images.
 */
public record PatchWorkOrderRequest(
        String status,
        String technicianNotes,
        String cancellationReason,
        List<SensorPatchDto> sensors,
        ActivityLogPatchDto activityLogEntry,

        // ── Installation (legacy): devices to create from a typed serial ──
        List<NewDeviceDto> newDevices,

        // ── Installation (discovery): claim sensors already reported by the edge ──
        List<ClaimDeviceDto> claimedDevices,

        // ── Maintenance / Collection: device status changes ──
        List<DeviceStatusDto> deviceUpdates,

        // ── Maintenance: actions performed ──
        List<MaintenanceActionPatchDto> maintenanceActions,

        // ── Evidence images (base64 data-URLs) ──
        List<String> evidence
) {
    public record SensorPatchDto(
            String sensorId,
            String location,
            String status
    ) {}

    public record ActivityLogPatchDto(
            String event,
            String time
    ) {}

    public record NewDeviceDto(
            String name,
            String type,
            String serialNumber
    ) {}

    /**
     * Claim a discovered sensor during an installation. The technician only
     * provides name + type (and the identity: deviceId from the discovery list,
     * or serialNumber). Client and location are set by the backend from the
     * work order — never typed by the technician.
     */
    public record ClaimDeviceDto(
            Long deviceId,
            String serialNumber,
            String name,
            String type
    ) {}

    public record DeviceStatusDto(
            Long deviceId,
            String status,
            String observation
    ) {}

    public record MaintenanceActionPatchDto(
            Long deviceId,
            String deviceName,
            String action,
            String description
    ) {}
}
