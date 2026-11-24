package com.emsafe.devicemanagement.application;

import com.emsafe.devicemanagement.application.ports.DeviceAlertGateway;
import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway;
import com.emsafe.devicemanagement.domain.events.DeviceInactivityDetected;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceInactivityAlertHandlerTest {
    private static final Instant LAST = Instant.parse("2026-11-23T14:00:00Z");
    private static final Instant NOW = LAST.plusSeconds(300);

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DosimetrySessionGateway sessions = mock(DosimetrySessionGateway.class);
    private final DeviceAlertGateway alerts = mock(DeviceAlertGateway.class);
    private final DeviceInactivityAlertHandler handler = new DeviceInactivityAlertHandler(devices, sessions, alerts);

    @Test
    void aSilentDosimeterInAnOpenSessionWarnsItsWorker() {
        device("DOS-1", DeviceType.PERSONAL_DOSIMETER, 7L);
        when(sessions.sessionCovering(1L, NOW)).thenReturn(Optional.of("s-1"));
        when(sessions.workerOf("s-1")).thenReturn(Optional.of(42L));

        handler.on(new DeviceInactivityDetected("DOS-1", 7L, LAST, NOW));

        verify(alerts).dosimeterSilent(7L, 42L, "s-1", "DOS-1", LAST);
    }

    @Test
    void aDosimeterOutsideASessionDoesNotWarn() {
        device("DOS-1", DeviceType.PERSONAL_DOSIMETER, 7L);
        when(sessions.sessionCovering(any(), any())).thenReturn(Optional.empty());

        handler.on(new DeviceInactivityDetected("DOS-1", 7L, LAST, NOW));

        verifyNoInteractions(alerts);
    }

    @Test
    void aSilentHomeSensorWarnsItsOwnerAndPlugsStayQuiet() {
        device("SEN-1", DeviceType.FIXED_SENSOR, 9L);
        device("PLUG-1", DeviceType.SMART_PLUG, 9L);

        handler.on(new DeviceInactivityDetected("SEN-1", 9L, LAST, NOW));
        handler.on(new DeviceInactivityDetected("PLUG-1", 9L, LAST, NOW));

        verify(alerts).homeSensorSilent(9L, "SEN-1", LAST);
        verifyNoInteractions(sessions);
    }

    private void device(String serial, DeviceType type, Long organizationId) {
        Device device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        when(device.getDeviceType()).thenReturn(type);
        when(device.organizationId()).thenReturn(organizationId);
        when(devices.findBySerial(serial)).thenReturn(Optional.of(device));
    }
}
