package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.personaldosimetry.application.PersonalDosimetryService;
import com.emsafe.personaldosimetry.domain.model.ExposureSession;
import com.emsafe.personaldosimetry.infrastructure.persistence.ExposureSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Cierra automáticamente ({@code CLOSING → CLOSED} con {@code deliveryConfirmed = false})
 * las jornadas cuyo plazo de reconciliación ({@code reconciliationDeadline}) ya venció.
 *
 * <p>Sin esta tarea, una jornada cerrada desde el portal con {@code POST /sessions/{id}/close}
 * quedaría indefinidamente en {@code CLOSING} a menos que alguien invocara manualmente
 * {@code /finalize}: no emitiría {@code ExposureSessionClosed}, no cerraría el turno vinculado
 * en Occupational Health ni entraría en la analítica histórica de AI Forecasting.</p>
 *
 * <p>Cada jornada del lote se finaliza en su propia transacción dentro de
 * {@link PersonalDosimetryService#finalizeExpiredClosingSession(String)} con bloqueo pesimista,
 * de modo que una llamada manual concurrente es idempotente y un fallo puntual no deshace el
 * resto del lote. En el perfil {@code test} se desactiva con
 * {@code emsafe.personal-dosimetry.reconciliation-enabled=false}.</p>
 */
@Component
@ConditionalOnProperty(name = "emsafe.personal-dosimetry.reconciliation-enabled",
        havingValue = "true", matchIfMissing = true)
public class DosimetryReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(DosimetryReconciliationJob.class);

    private final ExposureSessionRepository sessions;
    private final PersonalDosimetryService service;
    private final Clock clock;
    private final int batchSize;

    public DosimetryReconciliationJob(
            ExposureSessionRepository sessions,
            PersonalDosimetryService service,
            Clock clock,
            @Value("${emsafe.personal-dosimetry.reconciliation-batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("reconciliation-batch-size must be between 1 and 1000");
        }
        this.sessions = sessions;
        this.service = service;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${emsafe.personal-dosimetry.reconciliation-delay-ms:60000}",
            initialDelayString = "${emsafe.personal-dosimetry.reconciliation-delay-ms:60000}")
    public void reconcileScheduled() {
        try {
            reconcileOnce();
        } catch (RuntimeException e) {
            log.warn("Dosimetry session reconciliation pass failed: {}", e.toString());
        }
    }

    /**
     * Ejecuta una pasada de reconciliación sobre como máximo {@code batchSize} jornadas vencidas.
     *
     * @return cantidad de jornadas que efectivamente pasaron de {@code CLOSING} a {@code CLOSED}
     */
    public int reconcileOnce() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<String> expiredIds = sessions.findExpiredReconciliationIds(
                ExposureSession.State.CLOSING, now, PageRequest.of(0, batchSize));
        int finalized = 0;
        for (String id : expiredIds) {
            try {
                if (service.finalizeExpiredClosingSession(id)) {
                    finalized++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not auto-finalize expired closing session {}: {}", id, e.toString());
            }
        }
        if (finalized > 0) {
            log.info("Dosimetry reconciliation finalized {} of {} expired closing session(s)",
                    finalized, expiredIds.size());
        }
        return finalized;
    }
}
