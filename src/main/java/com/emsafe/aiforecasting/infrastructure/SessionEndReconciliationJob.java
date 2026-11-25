package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.CloseEndedSessionAssessmentsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cada minuto cierra con SESSION_ENDED las evaluaciones de jornadas que ya terminaron en Personal
 * Dosimetry. Es la vía sin tocar código de Personal Dosimetry: este contexto no recibe el cierre de
 * jornada, así que lo consulta por su fachada. Las consultas ya filtran por su cuenta
 * ({@code OpenSessionAssessments}); esta tarea deja además el estado guardado coherente.
 *
 * <p>Se apaga con {@code emsafe.ai-forecasting.session-end-reconcile-enabled=false} (perfil de test).</p>
 */
@Component
@ConditionalOnProperty(name = "emsafe.ai-forecasting.session-end-reconcile-enabled",
        havingValue = "true", matchIfMissing = true)
public class SessionEndReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(SessionEndReconciliationJob.class);

    private final CloseEndedSessionAssessmentsHandler handler;

    public SessionEndReconciliationJob(CloseEndedSessionAssessmentsHandler handler) {
        this.handler = handler;
    }

    @Scheduled(fixedDelayString = "${emsafe.ai-forecasting.session-end-reconcile-delay-ms:60000}",
            initialDelayString = "${emsafe.ai-forecasting.session-end-reconcile-delay-ms:60000}")
    public void runScheduled() {
        try {
            reconcileOnce();
        } catch (RuntimeException e) {
            log.warn("Session-end reconciliation pass failed: {}", e.toString());
        }
    }

    /** @return evaluaciones cerradas en esta pasada */
    public int reconcileOnce() {
        int closed = 0;
        for (Long organizationId : handler.organizationsWithActiveAssessments()) {
            try {
                closed += handler.closeEnded(organizationId);
            } catch (RuntimeException e) {
                log.warn("Could not close ended-session assessments of organization {}: {}", organizationId,
                        e.toString());
            }
        }
        if (closed > 0) {
            log.info("Closed {} assessment(s) whose session already ended (SESSION_ENDED)", closed);
        }
        return closed;
    }
}
