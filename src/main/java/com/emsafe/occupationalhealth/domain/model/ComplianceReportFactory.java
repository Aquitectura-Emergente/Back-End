package com.emsafe.occupationalhealth.domain.model;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Construye un reporte, sus entradas y su digest (informe §5.6.1).
 *
 * Vive en domain.model y no en domain.services —donde el informe lo agrupa—
 * porque es el único que puede componer un ComplianceReport: addEntry() y
 * seal() son de paquete a propósito, para que nadie pueda añadir filas a un
 * reporte ya generado desde fuera del agregado. La alternativa era hacerlos
 * públicos, y entonces la inmutabilidad de la regla 7 dejaría de estar
 * protegida por el compilador.
 */
@Component
public class ComplianceReportFactory {

    /**
     * Reporte por trabajador: relaciona cada jornada cerrada con las
     * excedencias, las alertas y las acciones que le corresponden.
     *
     * Cada entrada arrastra sus referencias de origen, que es lo que hace
     * contrastable el 100 % de las cifras (QAS-07). Una jornada con cobertura
     * incompleta se marca con una TraceabilityNote en vez de descartarse: el
     * dato parcial informado es mejor que un hueco silencioso.
     */
    public ComplianceReport forWorker(Long organizationId, Long workerId, ReportPeriod period,
                                      int version, Long generatedBy,
                                      List<MitigationAction> allActions,
                                      List<ClosedSessionRecord> sessions,
                                      List<AlertEvidenceRecord> alerts) {
        // Una propuesta desplazada no es una medida tomada ni constancia de que
        // no hubiera alternativa: queda fuera del reporte. Sigue en la bitácora
        // del agregado, que es append-only.
        List<MitigationAction> actions = allActions.stream()
                .filter(a -> a.getState().isReportableEvidence())
                .toList();

        ComplianceReport report = ComplianceReport.builder()
                .organizationId(organizationId)
                .scope(ReportScope.WORKER)
                .subjectWorkerId(workerId)
                .period(period)
                .version(version)
                .generatedBy(generatedBy)
                .build();

        Map<String, List<AlertEvidenceRecord>> alertsBySession = alerts.stream()
                .filter(a -> a.getSessionId() != null)
                .collect(Collectors.groupingBy(AlertEvidenceRecord::getSessionId));

        for (ClosedSessionRecord session : sessions) {
            ReportEntry entry = ReportEntry.builder()
                    .organizationId(organizationId)
                    .sessionId(session.getSessionId())
                    .entryDate(session.getFinalizedAt().toLocalDate())
                    .finalDose(session.getFinalDose())
                    .build();

            entry.getSources().add(ReportEntrySource.session(session.getSessionId()));
            alertsBySession.getOrDefault(session.getSessionId(), List.of())
                    .forEach(a -> entry.getSources().add(ReportEntrySource.alert(a.getAlertId())));
            actionsOf(actions, session).forEach(
                    a -> entry.getSources().add(ReportEntrySource.action(a.getId())));

            if (session.hasIncompleteCoverage()) {
                entry.getTraceNotes().add(TraceabilityNote.forIncompleteCoverage(session));
            }
            report.addEntry(entry);
        }

        // Las acciones que no cuelgan de ninguna jornada del período entran como
        // entradas propias: una medida tomada es evidencia aunque su jornada no
        // haya cerrado todavía, y perderla dejaría el reporte incompleto.
        for (MitigationAction orphan : actionsWithoutSession(actions, sessions)) {
            ReportEntry entry = ReportEntry.builder()
                    .organizationId(organizationId)
                    .entryDate(orphan.getCreatedAt().toLocalDate())
                    .build();
            entry.getSources().add(ReportEntrySource.action(orphan.getId()));
            if (orphan.getTrigger() != null && orphan.getTrigger().getSourceEventId() != null) {
                entry.getSources().add(ReportEntrySource.of(
                        ReportEntrySource.SourceType.EXCEEDANCE,
                        orphan.getTrigger().getSourceEventId()));
            }
            report.addEntry(entry);
        }

        report.seal();
        return report;
    }

    /**
     * Reporte consolidado por zona (US62 escenario 2, RF32).
     *
     * No recibe jornadas ni acciones: solo estadísticas de zona. Que la firma no
     * admita datos de personas es deliberado — así no hay forma de filtrar un
     * identificador de operario en un reporte de este alcance.
     */
    public ComplianceReport forPlantZones(Long organizationId, ReportPeriod period,
                                          int version, Long generatedBy,
                                          List<ZoneStatistics> zoneStats) {
        ComplianceReport report = ComplianceReport.builder()
                .organizationId(organizationId)
                .scope(ReportScope.PLANT_ZONE)
                .subjectWorkerId(null)
                .period(period)
                .version(version)
                .generatedBy(generatedBy)
                .build();

        for (ZoneStatistics stats : zoneStats) {
            ReportEntry entry = ReportEntry.builder()
                    .organizationId(organizationId)
                    .zoneAggregate(ZoneAggregate.of(stats.zoneId(), stats.average(),
                            stats.max(), stats.readingCount()))
                    .build();
            // La referencia de origen de una cifra agregada es la zona misma:
            // sin ella la entrada no pasaría el control de trazabilidad.
            entry.getSources().add(ReportEntrySource.of(
                    ReportEntrySource.SourceType.SESSION, "zone-" + stats.zoneId()));
            report.addEntry(entry);
        }

        report.seal();
        return report;
    }

    /** Acciones cuyo disparador apunta a la jornada indicada. */
    private List<MitigationAction> actionsOf(List<MitigationAction> actions,
                                             ClosedSessionRecord session) {
        return actions.stream()
                .filter(a -> a.getCreatedAt() != null)
                .filter(a -> a.getCreatedAt().toLocalDate()
                        .equals(session.getFinalizedAt().toLocalDate()))
                .toList();
    }

    private List<MitigationAction> actionsWithoutSession(List<MitigationAction> actions,
                                                         List<ClosedSessionRecord> sessions) {
        Function<MitigationAction, java.time.LocalDate> day =
                a -> a.getCreatedAt() != null ? a.getCreatedAt().toLocalDate() : null;
        List<java.time.LocalDate> covered = sessions.stream()
                .map(s -> s.getFinalizedAt().toLocalDate())
                .toList();
        return actions.stream()
                .filter(a -> day.apply(a) == null || !covered.contains(day.apply(a)))
                .toList();
    }
}
