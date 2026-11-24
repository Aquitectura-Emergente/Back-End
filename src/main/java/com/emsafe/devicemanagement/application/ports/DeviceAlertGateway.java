package com.emsafe.devicemanagement.application.ports;

import java.time.Instant;

/** Alertas, visto desde Device Management: avisos de un dispositivo que dejó de transmitir. */
public interface DeviceAlertGateway {
    /** Dosímetro de una jornada abierta: el aviso es del operario y lo ve el SSO de su organización. */
    void dosimeterSilent(Long organizationId, Long workerId, String sessionId, String deviceId, Instant lastSeenAt);

    /** Sensor del hogar: el aviso es del cliente dueño. */
    void homeSensorSilent(Long clientId, String deviceId, Instant lastSeenAt);
}
