package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.application.ports.PlantEdgeGateway;
import com.emsafe.devicemanagement.domain.model.ConnectivityStatus;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.EdgeNode;
import com.emsafe.devicemanagement.domain.model.EdgeSegment;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.domain.repositories.EdgeNodeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** Conectividad de dispositivos y de nodos Edge. */
@Service
@RequiredArgsConstructor
public class DeviceConnectivityService {
    private static final Logger log = LoggerFactory.getLogger(DeviceConnectivityService.class);

    private final DeviceRepository devices;
    private final EdgeNodeRepository edges;
    private final PlantEdgeGateway plantEdges;
    private final Clock clock;

    /** {@code iot/{deviceId}/status}: el Edge conectó o perdió el BLE con el dispositivo. */
    @Transactional
    public void recordDeviceStatus(String deviceId, String edgeNodeId, ConnectivityStatus status, Instant changedAt,
                                   String firmwareVersion, Integer batteryLevel) {
        Device device = devices.lockBySerial(deviceId).orElse(null);
        if (device == null) {
            log.warn("Status for unknown device {} ignored", deviceId);
            return;
        }
        device.reportHardware(firmwareVersion, batteryLevel);
        device.recordConnectivity(status, changedAt, edgeNodeId, "STATUS", clock.instant());
        devices.save(device);
    }

    /**
     * {@code edge/{edgeNodeId}/status}. El Last Will no trae hora: se usa la de
     * recepción. Un Edge de planta se informa también a Plant Configuration.
     */
    @Transactional
    public void recordEdgeStatus(String edgeNodeId, ConnectivityStatus status, Instant changedAt) {
        Instant at = changedAt != null ? changedAt : clock.instant();
        EdgeNode edge = edges.findByEdgeNodeId(edgeNodeId).orElse(null);
        if (edge == null) {
            log.warn("Status for unregistered edge {} ({})", edgeNodeId, status);
            plantEdges.recordConnectivity(edgeNodeId, status == ConnectivityStatus.ONLINE, at);
            return;
        }
        if (edge.changeConnectivity(status, at)) {
            log.info("Edge {} is {}", edgeNodeId, status);
        }
        edges.save(edge);
        if (edge.getSegment() == EdgeSegment.PLANT) {
            plantEdges.recordConnectivity(edgeNodeId, status == ConnectivityStatus.ONLINE, at);
        }
    }

    /** Dispositivos ONLINE que dejaron de dar noticias pasan a OFFLINE. */
    @Transactional
    public int detectInactiveDevices() {
        Instant now = clock.instant();
        int count = 0;
        for (Device device : devices.findOnline()) {
            if (device.detectInactivity(now)) {
                devices.save(device);
                log.info("Device {} inactive since {}; now OFFLINE", device.getSerialNumber(), device.getLastSeenAt());
                count++;
            }
        }
        return count;
    }
}
