package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.alerts.application.AlarmService;
import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import com.emsafe.alerts.interfaces.rest.dto.EscalateAlarmRequest;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;

/**
 * Escala al Responsable de SSO la alerta de excedencia que el operario no acusó
 * a tiempo (informe §5.5, escalamiento a los 60 s; RF23, RNF02).
 *
 * <p>Se agregó el 2026-11-22. Cada {@code alert-escalation-check-ms} busca las
 * alertas {@code danger} de operario sin acuse ni escalamiento creadas hace más
 * de {@code alert-ack-window-seconds} y las escala con
 * {@link AlarmService#escalate}, que deja la evidencia en este contexto (y, al
 * ser un escalamiento sin acuse, sube la prioridad de la medida: regla 10).</p>
 *
 * <ul>
 *   <li>Solo mira alertas de las últimas {@code alert-escalation-max-age-hours}:
 *       así no escala de golpe alertas viejas sembradas o de días anteriores.</li>
 *   <li>El destinatario es el SSO de la organización del operario (el de menor
 *       id si hay varios). Sin SSO no hay a quién escalar: se registra y se
 *       vuelve a intentar en la pasada siguiente.</li>
 *   <li>Con dos réplicas del backend las dos escalarían la misma alerta; el
 *       agregado lo hace idempotente (la segunda no mueve la fecha ni publica),
 *       pero para la demo va una sola réplica.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEscalationJob {

    private final AlarmService alarms;
    private final UserRepository users;

    /**
     * Apaga solo la pasada programada (el perfil de test lo pone en false); la
     * lógica sigue disponible en {@link #escalateOverdue} para ejercitarla.
     */
    @Value("${emsafe.occupational-health.alert-escalation-enabled:true}")
    private boolean enabled;

    @Value("${emsafe.occupational-health.alert-ack-window-seconds:60}")
    private long ackWindowSeconds;

    @Value("${emsafe.occupational-health.alert-escalation-max-age-hours:12}")
    private long maxAgeHours;

    @Scheduled(fixedDelayString = "${emsafe.occupational-health.alert-escalation-check-ms:15000}")
    public void runScheduled() {
        if (!enabled) return;
        try {
            escalateOverdue(LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("Alert escalation pass failed: {}", e.toString());
        }
    }

    /**
     * Una pasada. Público y con la hora como parámetro para poder probarlo sin
     * esperar al scheduler.
     *
     * @return alertas escaladas
     */
    public int escalateOverdue(LocalDateTime now) {
        int escalated = 0;
        for (AlertDto alert : alarms.awaitingEscalation(now.minusHours(maxAgeHours),
                now.minusSeconds(ackWindowSeconds))) {
            Optional<Long> sso = ssoOf(alert.organizationId());
            if (sso.isEmpty()) {
                log.warn("Alert {} cannot be escalated: organization {} has no SSO responsible",
                        alert.id(), alert.organizationId());
                continue;
            }
            try {
                alarms.escalate(alert.id(), new EscalateAlarmRequest(sso.get(),
                        "Escalamiento automático: el operario no acusó la alerta en "
                                + ackWindowSeconds + " s"));
                escalated++;
            } catch (RuntimeException e) {
                log.warn("Could not escalate alert {}: {}", alert.id(), e.toString());
            }
        }
        if (escalated > 0) {
            log.info("Escalated {} unacknowledged alert(s) to the SSO", escalated);
        }
        return escalated;
    }

    private Optional<Long> ssoOf(Long organizationId) {
        return users.findByRoleAndOrganizationId(Role.SSO_RESPONSIBLE, organizationId).stream()
                .map(AppUser::getId)
                .min(Comparator.naturalOrder());
    }
}
