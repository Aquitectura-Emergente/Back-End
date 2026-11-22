package com.emsafe.alerts.infrastructure.notification;

import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Empuja las alertas de operario por WebSocket (STOMP), el mismo mecanismo que
 * ya usan las lecturas del hogar ({@code StompHomeReadingNotifier}).
 *
 * <p>Tópicos (contrato con la app y el portal, agregado el 2026-11-22):</p>
 * <ul>
 *   <li>{@code /topic/workers/{workerId}/alerts}: la app del operario.</li>
 *   <li>{@code /topic/organizations/{organizationId}/alerts}: el portal del SSO.</li>
 * </ul>
 * <p>El payload es el mismo {@link AlertDto} de {@code /api/alarms}. Se envía al
 * crear, acusar y escalar, así los dos clientes ven el estado vigente.</p>
 *
 * <p><b>Se envía después del commit.</b> Si la transacción que creó la alerta se
 * deshace (por ejemplo, la pasada del relay falla), no debe llegar a la app una
 * alerta con un id que no existe.</p>
 *
 * <p>⚠ {@code /ws} sigue sin autenticación (tarea de IAM/Device Management): hoy
 * cualquiera que conozca el id puede suscribirse a estos tópicos.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerAlertNotifier {

    private final SimpMessagingTemplate messaging;

    public void notify(AlertDto alert) {
        if (alert.workerId() == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(alert);
                }
            });
        } else {
            send(alert);
        }
    }

    private void send(AlertDto alert) {
        try {
            messaging.convertAndSend("/topic/workers/" + alert.workerId() + "/alerts", alert);
            if (alert.organizationId() != null) {
                messaging.convertAndSend("/topic/organizations/" + alert.organizationId() + "/alerts", alert);
            }
        } catch (RuntimeException e) {
            // El aviso en vivo es una comodidad: la alerta ya está guardada y la
            // app la ve al consultar /api/alarms/mine.
            log.warn("Could not push alert {} over STOMP: {}", alert.id(), e.toString());
        }
    }
}
