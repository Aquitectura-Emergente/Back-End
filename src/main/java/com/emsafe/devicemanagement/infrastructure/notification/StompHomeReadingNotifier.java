package com.emsafe.devicemanagement.infrastructure.notification;

import com.emsafe.devicemanagement.application.ports.HomeReadingGateway.RecordedHomeReading;
import com.emsafe.devicemanagement.application.ports.HomeReadingNotifier;
import com.emsafe.devicemanagement.interfaces.rest.dto.ReadingDto;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Mismo mecanismo que antes: WebSocket (STOMP) con el mismo payload para web y
 * app. El tópico global lo usan el mapa y el panel; el del cliente, la app.
 */
@Component
@RequiredArgsConstructor
public class StompHomeReadingNotifier implements HomeReadingNotifier {
    private final SimpMessagingTemplate messaging;

    @Override
    public void notify(RecordedHomeReading r, boolean unregisteredDevice) {
        ReadingDto dto = ReadingDto.builder()
                .id(r.readingId())
                .serialNumber(r.serialNumber())
                .deviceDbId(r.deviceId())
                .deviceName(r.deviceName())
                .fieldUT(r.fieldUT())
                .level(r.level())
                .message(r.message())
                .location(r.location())
                .latitude(r.latitude())
                .longitude(r.longitude())
                .clientId(r.clientId())
                .clientName(r.clientName())
                .recordedAt(r.recordedAt())
                .build();
        messaging.convertAndSend("/topic/readings", dto);
        if (r.clientId() != null) {
            messaging.convertAndSend("/topic/clients/" + r.clientId() + "/readings", dto);
        }
        if (unregisteredDevice) {
            messaging.convertAndSend("/topic/discovery", dto);
        }
    }
}
