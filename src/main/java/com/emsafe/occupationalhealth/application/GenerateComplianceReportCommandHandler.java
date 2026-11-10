package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.ComplianceReportGenerated;
import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.domain.model.*;
import com.emsafe.occupationalhealth.domain.services.ReportRenderer;
import com.emsafe.occupationalhealth.domain.services.ReportStoragePort;
import com.emsafe.occupationalhealth.domain.services.ZoneExposurePort;
import com.emsafe.occupationalhealth.infrastructure.MitigationAuditAdapter;
import com.emsafe.occupationalhealth.infrastructure.persistence.AlertEvidenceRecordRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.ClosedSessionRecordRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.ComplianceReportRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Genera un reporte de cumplimiento (informe §5.6.3, RF31 y US62).
 *
 * Reúne las jornadas, excedencias y acciones del período, construye el reporte
 * con su digest y guarda el archivo. Pedir el mismo reporte otra vez **no
 * sobrescribe**: crea una versión nueva con su propio digest, como exige la
 * regla 7.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerateComplianceReportCommandHandler {

    private final ComplianceReportRepository reportRepository;
    private final ClosedSessionRecordRepository closedSessions;
    private final AlertEvidenceRecordRepository alertEvidence;
    private final MitigationActionRepository actionRepository;
    private final ZoneExposurePort zoneExposurePort;
    private final ComplianceReportFactory factory;
    private final ReportRenderer renderer;
    private final ReportStoragePort storage;
    private final MitigationAuditAdapter audit;
    private final ApplicationEventPublisher events;

    @Transactional
    public ComplianceReport handle(Long organizationId, ReportScope scope, Long workerId,
                                   ReportPeriod period, Long generatedBy) {
        if (scope.allowsIndividualData() && workerId == null) {
            throw new MitigationUnprocessableException(
                    "A WORKER report requires a workerId");
        }
        if (!scope.allowsIndividualData() && workerId != null) {
            throw new MitigationUnprocessableException(
                    "A PLANT_ZONE report cannot target a worker (RF32, RNF11)");
        }

        int version = nextVersion(organizationId, scope, workerId, period);

        ComplianceReport report = (scope == ReportScope.WORKER)
                ? buildForWorker(organizationId, workerId, period, version, generatedBy)
                : factory.forPlantZones(organizationId, period, version, generatedBy,
                        zoneExposurePort.zoneStatistics(organizationId, period));

        ComplianceReport saved = reportRepository.save(report);

        // El archivo se produce en la misma transacción que el reporte: si la
        // escritura falla, no queda un reporte sin su PDF.
        byte[] rendered = renderer.render(saved);
        String reference = storage.store(saved, rendered, renderer.fileExtension());
        saved.markExported(reference);
        saved = reportRepository.save(saved);

        audit.recordGranted(organizationId, generatedBy, "ComplianceReport", saved.getId(),
                MitigationAuditEntry.ACTION_GENERATE_REPORT,
                scope + " report for period " + period + ", version " + version);

        events.publishEvent(new ComplianceReportGenerated(
                EventEnvelope.of(organizationId), saved.getId(), saved.getScope().name(),
                period.toString(), saved.getVersion(),
                saved.getEvidenceDigest().getValue()));

        log.info("Generated compliance report {} (scope={}, version={}, entries={})",
                saved.getId(), scope, version, saved.getEntries().size());
        return saved;
    }

    /**
     * El contenido del archivo, para servirlo en el export.
     *
     * No es readOnly porque cada descarga deja una línea de auditoría: el
     * informe exige registrar toda exportación de reporte, al ser contenido con
     * datos de salud.
     */
    @Transactional
    public byte[] export(ComplianceReport report, Long actorId) {
        byte[] content = (report.getFileReference() == null)
                // Un reporte sin archivo es recuperable: se vuelve a dibujar desde
                // su contenido, que es inmutable, así que sale lo mismo.
                ? renderer.render(report)
                : storage.retrieve(report.getFileReference());

        audit.recordGranted(report.getOrganizationId(), actorId, "ComplianceReport",
                report.getId(), MitigationAuditEntry.ACTION_EXPORT_REPORT,
                "Exported version " + report.getVersion()
                        + " (" + content.length + " bytes)");
        return content;
    }

    private ComplianceReport buildForWorker(Long organizationId, Long workerId,
                                            ReportPeriod period, int version, Long generatedBy) {
        List<ClosedSessionRecord> sessions = closedSessions.findByWorkerAndPeriod(
                organizationId, workerId, period.startOfPeriod(), period.endOfPeriod());
        List<MitigationAction> actions = actionRepository.findByWorkerAndPeriod(
                organizationId, workerId, period.startOfPeriod(), period.endOfPeriod());
        List<AlertEvidenceRecord> alerts = alertEvidence.findByWorker(organizationId, workerId);

        return factory.forWorker(organizationId, workerId, period, version, generatedBy,
                sessions, actions, alerts);
    }

    /**
     * La siguiente versión para ese alcance, sujeto y período. Empieza en 1 y
     * sube de uno en uno: el UNIQUE de la tabla impide que dos peticiones
     * simultáneas obtengan la misma.
     */
    private int nextVersion(Long organizationId, ReportScope scope, Long workerId,
                            ReportPeriod period) {
        Integer max = reportRepository.findMaxVersion(organizationId, scope, workerId,
                period.getFrom(), period.getTo());
        return max == null ? 1 : max + 1;
    }
}
