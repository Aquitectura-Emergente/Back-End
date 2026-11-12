package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Una fila del reporte, con su referencia de origen (informe §5.6.1).
 *
 * No tiene setters públicos: una entrada de un reporte generado no se corrige.
 * Si hay que cambiar algo, se genera una versión nueva del reporte entero
 * (regla 7).
 */
@Entity
@Table(name = "report_entries")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compliance_report_id", nullable = false)
    @Setter(AccessLevel.PACKAGE)
    private ComplianceReport report;

    /** Jornada de exposición de la que sale la cifra. Nulo en reportes por zona. */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** Dosis de la jornada. Solo en entradas de alcance WORKER. */
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "final_dose", precision = 12, scale = 4))
    private DoseSnapshot finalDose;

    /** Agregados de la zona. Solo en entradas de alcance PLANT_ZONE. */
    @Embedded
    private ZoneAggregate zoneAggregate;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "report_entry_sources",
            joinColumns = @JoinColumn(name = "report_entry_id"))
    @BatchSize(size = 50)
    @Builder.Default
    private List<ReportEntrySource> sources = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "report_entry_trace_notes",
            joinColumns = @JoinColumn(name = "report_entry_id"))
    @BatchSize(size = 50)
    @Builder.Default
    private List<TraceabilityNote> traceNotes = new ArrayList<>();

    // ─── Comportamiento ───────────────────────────────────────────────────────

    /**
     * Las referencias que sustentan esta cifra. El informe exige que el 100 % de
     * los valores pueda contrastarse con su origen (QAS-07), así que una entrada
     * sin ninguna referencia es un defecto, no un caso válido.
     */
    public List<ReportEntrySource> sourceReferences() {
        return List.copyOf(sources);
    }

    public boolean hasSourceReference() {
        return !sources.isEmpty();
    }

    /** Contribución de esta entrada al contenido canónico del que sale el digest. */
    String canonicalForm() {
        StringBuilder sb = new StringBuilder();
        sb.append(sessionId).append('|')
                .append(entryDate).append('|')
                .append(finalDose != null ? finalDose.getValue() : null).append('|')
                .append(finalDose != null ? finalDose.getUnit() : null).append('|')
                .append(finalDose != null ? finalDose.getLimit() : null).append('|')
                .append(zoneAggregate != null ? zoneAggregate.getZoneId() : null).append('|')
                .append(zoneAggregate != null ? zoneAggregate.getAverageValue() : null).append('|')
                .append(zoneAggregate != null ? zoneAggregate.getMaxValue() : null).append('|');
        sources.stream()
                .sorted(java.util.Comparator
                        .comparing((ReportEntrySource s) -> s.getType().name())
                        .thenComparing(ReportEntrySource::getRef))
                .forEach(s -> sb.append(s.getType()).append(':').append(s.getRef()).append(','));
        sb.append('|');
        traceNotes.forEach(n -> sb.append(n.getCause()).append(','));
        return sb.toString();
    }
}
