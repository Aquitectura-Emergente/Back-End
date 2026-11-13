package com.emsafe.devicemanagement.infrastructure.scheduling;

import com.emsafe.devicemanagement.application.DeviceConnectivityService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Detección de dispositivos inactivos; corre en el contenedor de ingesta. */
@Component
@Profile("ingestion")
@RequiredArgsConstructor
public class InactivityMonitor {
    private static final Logger log = LoggerFactory.getLogger(InactivityMonitor.class);
    private final DeviceConnectivityService connectivity;

    @Scheduled(fixedDelayString = "${emsafe.device-management.inactivity-check-ms:30000}")
    public void check() {
        try {
            connectivity.detectInactiveDevices();
        } catch (RuntimeException e) {
            log.warn("Inactivity check failed: {}", e.toString());
        }
    }
}
