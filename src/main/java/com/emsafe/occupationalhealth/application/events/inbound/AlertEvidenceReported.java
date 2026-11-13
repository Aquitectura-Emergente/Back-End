package com.emsafe.occupationalhealth.application.events.inbound;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;

import java.time.LocalDateTime;

/**
 * Alerts → Mitigation (informe §5.6.3): AlertAcknowledged y AlertEscalated.
 *
 * Los dos hechos del informe viajan en un solo tipo con un discriminador porque
 * su efecto aquí es el mismo —sellar una marca en el mismo AlertEvidenceRecord—
 * y separarlos obligaría a duplicar el handler. El contrato externo sigue siendo
 * de dos eventos: `kind` es lo que los distingue.
 *
 * `occurredAt` es la hora REAL del suceso, no la de llegada. Es lo que permite
 * que una alerta sincronizada de forma diferida se notifique una sola vez e
 * indique cuándo ocurrió.
 *
 * Solo cuentan las alertas cloud: los avisos locales del dosímetro no generan
 * evidencia en este contexto.
 */
public record AlertEvidenceReported(
        EventEnvelope envelope,
        Kind kind,
        String alertId,
        Long workerId,
        String sessionId,
        String alertType,
        Long ssoResponsibleId,
        String reason,
        LocalDateTime occurredAt) {

    public enum Kind {
        /** El operario acusó recibo del aviso. */
        ACKNOWLEDGED,
        /** El aviso se escaló al Responsable de SSO. */
        ESCALATED
    }

    public boolean isEscalation() {
        return kind == Kind.ESCALATED;
    }
}
