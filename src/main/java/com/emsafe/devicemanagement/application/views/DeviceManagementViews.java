package com.emsafe.devicemanagement.application.views;

import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceAuditEntry;
import com.emsafe.devicemanagement.domain.model.DeviceConfig;
import com.emsafe.devicemanagement.domain.model.EdgeNode;
import com.emsafe.devicemanagement.domain.model.PlugCommand;
import com.emsafe.devicemanagement.domain.model.PlugState;
import com.emsafe.devicemanagement.domain.model.TelemetryReading;

import java.math.BigDecimal;
import java.time.Instant;

/** Vistas de lectura que devuelve la capa de aplicación. */
public final class DeviceManagementViews {
    private DeviceManagementViews() {
    }

    public record DeviceStatusView(Long id, String deviceId, String deviceType, String lifecycleStatus,
                                   String operationalStatus, String connectivity, Instant connectivityChangedAt,
                                   String connectivityEdgeNodeId, Instant lastSeenAt, String firmwareVersion,
                                   Integer batteryLevel, Long organizationId, Long plantZoneId) {
        public static DeviceStatusView from(Device d) {
            return new DeviceStatusView(d.getId(), d.getSerialNumber(), name(d.getDeviceType()),
                    name(d.getLifecycleStatus()), d.getStatus(), name(d.getConnectivity()),
                    d.getConnectivityChangedAt(), d.getConnectivityEdgeNodeId(), d.getLastSeenAt(),
                    d.getFirmwareVersion(), d.getBatteryLevel(), d.organizationId(), d.getPlantZoneId());
        }
    }

    public record DeviceConfigView(String deviceId, int configVersion, BigDecimal alertThresholdUT, String definedBy,
                                   long samplingIntervalMs, long inactivityTimeoutS, Instant updatedAt) {
        public static DeviceConfigView from(Device d) {
            DeviceConfig c = d.config();
            return new DeviceConfigView(d.getSerialNumber(), c.version(), c.alertThreshold().microtesla(),
                    c.definedBy().name(), c.samplingInterval().millis(), c.inactivityTimeout().seconds(),
                    c.updatedAt());
        }
    }

    public record AuditEntryView(Long id, String entryType, String field, String oldValue, String newValue,
                                 String actor, String details, Instant occurredAt) {
        public static AuditEntryView from(DeviceAuditEntry e) {
            return new AuditEntryView(e.getId(), e.getEntryType().name(), e.getFieldName(), e.getOldValue(),
                    e.getNewValue(), e.getActor(), e.getDetails(), e.getOccurredAt());
        }
    }

    public record TelemetryReadingView(Long id, String deviceId, String edgeNodeId, long sequence, Instant capturedAt,
                                       String capturedAtSource, Instant receivedAt, BigDecimal magneticFluxDensityUT,
                                       String origin, String quality, String bootId, String reportedType,
                                       String routing, String routingRef, String routingDetail) {
        public static TelemetryReadingView from(TelemetryReading r) {
            return new TelemetryReadingView(r.getId(), r.getDeviceSerial(), r.getEdgeNodeId(), r.getSequenceNumber(),
                    r.getCapturedAt(), r.getCapturedAtSource().name(), r.getReceivedAt(), r.getFluxUt(),
                    r.getOrigin().name(), r.getQuality().name(), r.getBootId(), r.getReportedType().name(),
                    r.getRouting().name(), r.getRoutingRef(), r.getRoutingDetail());
        }
    }

    public record EdgeNodeView(String edgeNodeId, Long organizationId, String segment, String connectivity,
                               Instant connectivityChangedAt, Instant lastSeenAt) {
        public static EdgeNodeView from(EdgeNode e) {
            return new EdgeNodeView(e.getEdgeNodeId(), e.getOrganizationId(), e.getSegment().name(),
                    e.getConnectivity().name(), e.getConnectivityChangedAt(), e.getLastSeenAt());
        }
    }

    public record PlugCommandView(String commandId, String plugId, String action, String status,
                                  Instant requestedAt, Instant resolvedAt) {
        public static PlugCommandView from(PlugCommand c) {
            return c == null ? null : new PlugCommandView(c.getCommandId(), c.getPlugSerial(), c.getAction().name(),
                    c.getStatus().name(), c.getRequestedAt(), c.getResolvedAt());
        }
    }

    /** Estado real del enchufe y la última orden; la app muestra "pendiente" mientras la orden siga PENDING. */
    public record PlugStateView(String plugId, String state, String commandId, String source, Instant changedAt,
                                PlugCommandView lastCommand) {
        public static PlugStateView from(Device plug, PlugCommand last) {
            PlugState s = plug.plugState();
            return new PlugStateView(plug.getSerialNumber(),
                    s == null ? name(plug.getPlugPower()) : s.power().name(),
                    s == null ? null : s.commandId(), s == null ? null : s.source().name(),
                    s == null ? null : s.changedAt(), PlugCommandView.from(last));
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
