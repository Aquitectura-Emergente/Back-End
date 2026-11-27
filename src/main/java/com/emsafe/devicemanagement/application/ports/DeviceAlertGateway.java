package com.emsafe.devicemanagement.application.ports;

import java.math.BigDecimal;
import java.time.Instant;

/** Alertas, visto desde Device Management: avisos de un dispositivo que dejó de transmitir o superó su umbral. */
public interface DeviceAlertGateway {
    /** Dosímetro de una jornada abierta: el aviso es del operario y lo ve el SSO de su organización. */
    void dosimeterSilent(Long organizationId, Long workerId, String sessionId, String deviceId, Instant lastSeenAt);

    /** El dosímetro midió por encima de su umbral instantáneo (el que hace sonar su alarma). */
    void dosimeterThresholdExceeded(Long organizationId, Long workerId, String sessionId, String deviceId,
                                    BigDecimal fluxUT, BigDecimal thresholdUT, Instant capturedAt);

    /** Sensor del hogar: el aviso es del cliente dueño. */
    void homeSensorSilent(Long clientId, String deviceId, Instant lastSeenAt);
}
