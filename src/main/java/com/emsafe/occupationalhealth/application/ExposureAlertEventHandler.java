package com.emsafe.occupationalhealth.application;

import com.emsafe.alerts.application.AlarmService;
import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.inbound.ExposureWarningRaised;
import com.emsafe.occupationalhealth.application.events.inbound.OccupationalLimitExceeded;
import com.emsafe.occupationalhealth.infrastructure.InboxDeduplicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Alerta automática al operario cuando Personal Dosimetry confirma una
 * excedencia o un aviso preventivo (informe §4.2.3 escenario 2, RF22, RF23).
 *
 * <p>Se agregó el 2026-11-22. Hasta entonces las alertas de operario solo se
 * creaban a mano ({@code POST /api/alarms}) o con el seeder. Vive en este
 * contexto porque Alerts ya no es un bounded context propio: la política de
 * cuándo y a quién avisar es de Occupational Health, y la alerta se guarda con
 * la fachada de Alerts ({@link AlarmService#raiseWorkerAlert}).</p>
 *
 * <ul>
 *   <li>{@link OccupationalLimitExceeded} → alerta {@code danger}. Si el operario
 *       no la acusa en la ventana, {@code AlertEscalationJob} la escala al SSO.</li>
 *   <li>{@link ExposureWarningRaised} → alerta {@code warning}. No se escala: es
 *       preventiva.</li>
 * </ul>
 *
 * <p>Es un consumidor aparte del que abre la medida de mitigación
 * ({@link OccupationalLimitExceededEventHandler}), con su propio nombre en el
 * inbox: así cada uno deduplica lo suyo y un reintento no duplica la alerta.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExposureAlertEventHandler {

    static final String CONSUMER = "ExposureAlertConsumer";

    private final AlarmService alarms;
    private final InboxDeduplicator inbox;

    @EventListener
    @Transactional
    public void on(OccupationalLimitExceeded event) {
        if (!accept(event.envelope(), event.workerId())) return;
        alarms.raiseWorkerAlert(new AlarmService.WorkerAlert(
                event.envelope().organizationId(), event.workerId(), event.sessionId(),
                "danger", "ph-warning-circle",
                "Límite de exposición superado",
                "Tu exposición acumulada (" + amount(event.dose(), event.unit())
                        + ") superó tu límite (" + amount(event.limit(), event.unit())
                        + "). Sal de la zona y avisa a tu responsable de SSO."));
        log.info("Raised limit-exceeded alert for worker {} (session {})",
                event.workerId(), event.sessionId());
    }

    @EventListener
    @Transactional
    public void on(ExposureWarningRaised event) {
        if (!accept(event.envelope(), event.workerId())) return;
        alarms.raiseWorkerAlert(new AlarmService.WorkerAlert(
                event.envelope().organizationId(), event.workerId(), event.sessionId(),
                "warning", "ph-warning",
                "Te acercas a tu límite de exposición",
                "Tu exposición acumulada (" + amount(event.dose(), event.unit())
                        + ") está cerca de tu límite (" + amount(event.limit(), event.unit()) + ")."));
        log.info("Raised exposure-warning alert for worker {} (session {})",
                event.workerId(), event.sessionId());
    }

    /**
     * Sin operario o sin organización no hay a quién avisar, y la alerta no se
     * podría acusar después (Alert exige los dos). Se registra y se descarta.
     */
    private boolean accept(EventEnvelope envelope, Long workerId) {
        if (envelope == null || envelope.organizationId() == null || workerId == null) {
            log.warn("Exposure event without organization or worker; no alert raised");
            return false;
        }
        return inbox.markAsProcessed(CONSUMER, envelope);
    }

    private static String amount(BigDecimal value, String unit) {
        if (value == null) return "sin dato";
        return value.setScale(0, RoundingMode.HALF_UP).toPlainString() + " " + (unit == null ? "" : unit);
    }
}
