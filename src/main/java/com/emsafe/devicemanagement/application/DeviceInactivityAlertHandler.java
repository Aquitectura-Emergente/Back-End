package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.application.ports.DeviceAlertGateway;
import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway;
import com.emsafe.devicemanagement.domain.events.DeviceInactivityDetected;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Aviso de "dispositivo sin señal" cuando pasa el tiempo de inactividad sin
 * noticias. Un dosímetro solo avisa si su operario está en una jornada
 * abierta; fuera de ella, que no transmita es lo normal. Los enchufes no
 * avisan: su estado ya lo muestra la app.
 */
@Component
@RequiredArgsConstructor
public class DeviceInactivityAlertHandler {
    private static final Logger log = LoggerFactory.getLogger(DeviceInactivityAlertHandler.class);

    private final DeviceRepository devices;
    private final DosimetrySessionGateway sessions;
    private final DeviceAlertGateway alerts;

    @EventListener
    public void on(DeviceInactivityDetected event) {
        Device device = devices.findBySerial(event.deviceId()).orElse(null);
        if (device == null) return;
        if (device.getDeviceType() == DeviceType.PERSONAL_DOSIMETER) {
            String sessionId = sessions.sessionCovering(device.getId(), event.occurredAt()).orElse(null);
            if (sessionId == null) return;
            Long workerId = sessions.workerOf(sessionId).orElse(null);
            if (workerId == null || device.organizationId() == null) return;
            alerts.dosimeterSilent(device.organizationId(), workerId, sessionId, event.deviceId(), event.lastSeenAt());
            log.info("Dosimeter {} silent since {}: alert for worker {}", event.deviceId(), event.lastSeenAt(),
                    workerId);
        } else if (device.getDeviceType() == DeviceType.FIXED_SENSOR && device.organizationId() != null) {
            alerts.homeSensorSilent(device.organizationId(), event.deviceId(), event.lastSeenAt());
            log.info("Home sensor {} silent since {}", event.deviceId(), event.lastSeenAt());
        }
    }
}
