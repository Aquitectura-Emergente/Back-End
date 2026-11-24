package com.emsafe.devicemanagement.infrastructure.acl;

import com.emsafe.alerts.application.AlarmService;
import com.emsafe.alerts.interfaces.rest.dto.CreateAlarmRequest;
import com.emsafe.devicemanagement.application.ports.DeviceAlertGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Capa anticorrupción hacia Alerts: crea la alerta con su servicio de aplicación. */
@Component
@RequiredArgsConstructor
public class AlertsDeviceAlertAdapter implements DeviceAlertGateway {
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("America/Lima"));

    private final AlarmService alarms;

    @Override
    public void dosimeterSilent(Long organizationId, Long workerId, String sessionId, String deviceId,
                                Instant lastSeenAt) {
        // Alerta de operario: la ve en vivo la app y el SSO de la organización.
        alarms.raiseWorkerAlert(new AlarmService.WorkerAlert(organizationId, workerId, sessionId, "warning",
                "ph-wifi-slash", "Dosímetro sin señal",
                "El dosímetro " + deviceId + " no transmite desde las " + hour(lastSeenAt)
                        + ". La exposición de la jornada no se está registrando."));
    }

    @Override
    public void homeSensorSilent(Long clientId, String deviceId, Instant lastSeenAt) {
        alarms.create(new CreateAlarmRequest("warning", "ph-wifi-slash", "Sensor sin señal",
                "El sensor " + deviceId + " no transmite desde las " + hour(lastSeenAt) + ".",
                null, "specific", List.of(clientId), deviceId, null, null, null));
    }

    private static String hour(Instant at) {
        return at == null ? "hora desconocida" : HOUR.format(at);
    }
}
