package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.application.views.DeviceManagementViews.AuditEntryView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.DeviceConfigView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.DeviceStatusView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.EdgeNodeView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.TelemetryReadingView;
import com.emsafe.devicemanagement.domain.model.AlertThreshold;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceLifecycleStatus;
import com.emsafe.devicemanagement.domain.model.InactivityTimeout;
import com.emsafe.devicemanagement.domain.model.ReadingRouting;
import com.emsafe.devicemanagement.domain.model.SamplingInterval;
import com.emsafe.devicemanagement.domain.repositories.DeviceAuditRepository;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.domain.repositories.EdgeNodeRepository;
import com.emsafe.devicemanagement.domain.repositories.TelemetryReadingRepository;
import com.emsafe.shared.dto.PageResponse;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * Casos de uso de configuración, ciclo de vida y consulta de un dispositivo.
 * El umbral lo define el responsable de SSO; muestreo, inactividad y ciclo de
 * vida, el administrador.
 */
@Service
@RequiredArgsConstructor
public class DeviceManagementService {
    private final DeviceRepository devices;
    private final DeviceAuditRepository audit;
    private final TelemetryReadingRepository readings;
    private final EdgeNodeRepository edges;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DeviceStatusView status(DeviceActor actor, String deviceId) {
        Device device = require(deviceId);
        actor.requireRead(device.organizationId());
        return DeviceStatusView.from(device);
    }

    @Transactional(readOnly = true)
    public DeviceConfigView config(DeviceActor actor, String deviceId) {
        Device device = require(deviceId);
        actor.requireRead(device.organizationId());
        return DeviceConfigView.from(device);
    }

    @Transactional
    public DeviceConfigView changeAlertThreshold(DeviceActor actor, String deviceId, BigDecimal thresholdUT) {
        Device device = require(deviceId);
        actor.requireSso(device.organizationId());
        if (thresholdUT == null) throw new BadRequestException("alertThresholdUT is required");
        device.changeAlertThreshold(new AlertThreshold(thresholdUT), actor.label(), clock.instant());
        return DeviceConfigView.from(devices.save(device));
    }

    @Transactional
    public DeviceConfigView changeOperationalConfig(DeviceActor actor, String deviceId, Long samplingIntervalMs,
                                                    Long inactivityTimeoutS) {
        actor.requireAdministrator();
        Device device = require(deviceId);
        device.changeOperationalConfig(
                samplingIntervalMs == null ? null : new SamplingInterval(samplingIntervalMs),
                inactivityTimeoutS == null ? null : new InactivityTimeout(inactivityTimeoutS),
                actor.label(), clock.instant());
        return DeviceConfigView.from(devices.save(device));
    }

    @Transactional
    public DeviceStatusView changeLifecycle(DeviceActor actor, String deviceId, String status) {
        actor.requireAdministrator();
        Device device = require(deviceId);
        device.changeLifecycle(parseLifecycle(status), actor.label(), clock.instant());
        return DeviceStatusView.from(devices.save(device));
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntryView> audit(DeviceActor actor, String deviceId, int page, int size) {
        Device device = require(deviceId);
        actor.requireRead(device.organizationId());
        if (page < 0 || size < 1 || size > 200) {
            throw new BadRequestException("Page must be non-negative and size between 1 and 200");
        }
        List<AuditEntryView> content = audit.findByDevice(device.getId(), page, size).stream()
                .map(AuditEntryView::from).toList();
        long total = audit.countByDevice(device.getId());
        int pages = (int) Math.ceil(total / (double) size);
        return new PageResponse<>(content, page, size, total, pages, page >= pages - 1);
    }

    @Transactional(readOnly = true)
    public List<TelemetryReadingView> telemetry(DeviceActor actor, String deviceId, String routing, int limit) {
        Device device = require(deviceId);
        actor.requireRead(device.organizationId());
        ReadingRouting filter = null;
        if (routing != null && !routing.isBlank()) {
            try {
                filter = ReadingRouting.valueOf(routing.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown routing: " + routing);
            }
        }
        return readings.findByDevice(device.getSerialNumber(), filter, Math.max(1, Math.min(limit, 500))).stream()
                .map(TelemetryReadingView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<EdgeNodeView> edges(DeviceActor actor) {
        actor.requireAdministrator();
        return edges.findAll().stream().map(EdgeNodeView::from).toList();
    }

    private Device require(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) throw new BadRequestException("deviceId is required");
        return devices.findBySerial(deviceId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Device not found: " + deviceId));
    }

    private static DeviceLifecycleStatus parseLifecycle(String value) {
        try {
            return DeviceLifecycleStatus.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("status must be IN_STOCK, INSTALLED or RETIRED");
        }
    }
}
