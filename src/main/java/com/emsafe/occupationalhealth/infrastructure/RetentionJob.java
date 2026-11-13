package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.occupationalhealth.domain.model.ComplianceReport;
import com.emsafe.occupationalhealth.domain.model.MitigationAuditEntry;
import com.emsafe.occupationalhealth.domain.services.ReportStoragePort;
import com.emsafe.occupationalhealth.infrastructure.persistence.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Elimina lo que superó su plazo de conservación (informe §5.6.6.2).
 *
 * Los plazos no son una decisión de diseño: los registros de este contexto
 * forman parte del registro de monitoreo de agentes físicos que el empleador
 * debe conservar **cinco años** según el artículo 35 del DS 005-2012-TR. Por eso
 * tampoco se borran ante una solicitud de supresión del trabajador mientras dure
 * ese plazo — eso es deliberado y está en el informe.
 *
 * La única excepción corta son los accesos denegados (1 año) y el inbox
 * (30 días), que no forman parte de ese registro legal.
 *
 * Corre una vez al mes. Es el único componente autorizado a borrar de las tablas
 * append-only; en el informe esto se refuerza con un rol retention_job en
 * PostgreSQL, que aquí queda pendiente para el despliegue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionJob {

    /** Plazo legal del registro de monitoreo (DS 005-2012-TR, art. 35). */
    private static final int LEGAL_RETENTION_YEARS = 5;
    /** Los accesos denegados no son parte de ese registro. */
    private static final int DENIED_ACCESS_RETENTION_YEARS = 1;
    /** El inbox solo existe para deduplicar; 30 días sobran. */
    private static final int INBOX_RETENTION_DAYS = 30;

    private final ComplianceReportRepository reports;
    private final MitigationActionRepository actions;
    private final WorkerShiftRepository shifts;
    private final ClosedSessionRecordRepository closedSessions;
    private final AlertEvidenceRecordRepository alertEvidence;
    private final MitigationAuditEntryRepository auditLog;
    private final ProcessedMessageRepository processedMessages;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ReportStoragePort storage;

    /** Permite apagarlo en entornos donde no se quiere que borre nada. */
    @Value("${emsafe.occupational-health.retention-enabled:true}")
    private boolean enabled;

    /** El día 1 de cada mes a las 03:00. */
    @Scheduled(cron = "${emsafe.occupational-health.retention-cron:0 0 3 1 * *}")
    @Transactional
    public void purgeExpired() {
        if (!enabled) {
            log.debug("Retention job is disabled");
            return;
        }
        LocalDateTime legal = LocalDateTime.now().minusYears(LEGAL_RETENTION_YEARS);
        LocalDateTime denied = LocalDateTime.now().minusYears(DENIED_ACCESS_RETENTION_YEARS);
        LocalDateTime inbox = LocalDateTime.now().minusDays(INBOX_RETENTION_DAYS);

        int removed = 0;
        removed += purgeReports(legal);
        removed += purge("mitigation actions", actions.findByCreatedAtBefore(legal), actions::deleteAll);
        removed += purge("worker shifts", shifts.findByCreatedAtBefore(legal), shifts::deleteAll);
        removed += purge("closed sessions",
                closedSessions.findByFinalizedAtBefore(legal), closedSessions::deleteAll);
        removed += purge("alert evidence",
                alertEvidence.findOlderThan(legal), alertEvidence::deleteAll);
        removed += purge("audit entries",
                auditLog.findByOutcomeAndOccurredAtBefore(MitigationAuditEntry.GRANTED, legal),
                auditLog::deleteAll);
        removed += purge("denied-access entries",
                auditLog.findByOutcomeAndOccurredAtBefore(MitigationAuditEntry.DENIED, denied),
                auditLog::deleteAll);
        removed += purge("processed messages",
                processedMessages.findByProcessedAtBefore(inbox), processedMessages::deleteAll);
        // Las claves de idempotencia solo cubren el reintento de una petición:
        // pasado el plazo del inbox ya no protegen de nada.
        removed += purge("idempotency keys",
                idempotencyRecords.findByCreatedAtBefore(inbox), idempotencyRecords::deleteAll);

        log.info("Retention job removed {} expired record(s)", removed);
    }

    /**
     * Los reportes se borran junto con su archivo. El archivo primero: si falla
     * el borrado de filas, queda un PDF huérfano señalado en el log, que es
     * preferible a una fila que apunte a un archivo que ya no está.
     */
    private int purgeReports(LocalDateTime threshold) {
        List<ComplianceReport> expired = reports.findByGeneratedAtBefore(threshold);
        if (expired.isEmpty()) return 0;
        expired.forEach(r -> storage.delete(r.getFileReference()));
        reports.deleteAll(expired);
        log.info("Retention: removed {} compliance report(s) and their files", expired.size());
        return expired.size();
    }

    private <T> int purge(String what, List<T> expired, java.util.function.Consumer<List<T>> delete) {
        if (expired.isEmpty()) return 0;
        delete.accept(expired);
        log.info("Retention: removed {} {}", expired.size(), what);
        return expired.size();
    }
}
