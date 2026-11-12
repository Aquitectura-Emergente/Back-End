package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Un resultado de auditoría que no admite cambios (informe §5.6.1).
 *
 * Es el agregado más restrictivo del contexto: se construye entero a través de
 * ComplianceReportFactory y después solo expone lectura. La única mutación
 * permitida es markExported(), que anota dónde quedó el archivo — el contenido
 * del reporte y su digest no se tocan nunca (regla 7).
 *
 * OJO con el campo `version`: es el número de versión del reporte (1, 2, 3…
 * para el mismo trabajador y período), NO un bloqueo optimista. No lleva
 * @Version a propósito: la tabla es append-only, así que no hay escrituras
 * concurrentes sobre una misma fila que haga falta arbitrar.
 */
@Entity
@Table(name = "compliance_reports")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ComplianceReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportScope scope;

    /** El trabajador del reporte. Nulo —y obligatoriamente nulo— por zona. */
    @Column(name = "subject_worker_id")
    private Long subjectWorkerId;

    @Embedded
    private ReportPeriod period;

    @Column(nullable = false)
    private Integer version;

    @Embedded
    private EvidenceDigest evidenceDigest;

    @Column(name = "file_reference", length = 500)
    private String fileReference;

    @Column(name = "exported_at")
    private LocalDateTime exportedAt;

    @Column(name = "generated_by", nullable = false)
    private Long generatedBy;

    @Column(name = "generated_at", nullable = false)
    @Builder.Default
    private LocalDateTime generatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "report",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<ReportEntry> entries = new ArrayList<>();

    // ─── Comportamiento del agregado ──────────────────────────────────────────

    /**
     * Añade una entrada y la vincula. Es de paquete a propósito: solo
     * ComplianceReportFactory, que vive en este paquete, puede componer un
     * reporte. Desde fuera no hay forma de añadir una fila a un reporte ya
     * generado.
     */
    void addEntry(ReportEntry entry) {
        if (scope == ReportScope.PLANT_ZONE && entry.getSessionId() != null) {
            throw new MitigationRuleException(
                    "A PLANT_ZONE report cannot carry session identifiers (RF32, RNF11)");
        }
        entry.setReport(this);
        entries.add(entry);
    }

    /** Sella el digest sobre el contenido ya compuesto. */
    void seal() {
        if (scope.allowsIndividualData() && subjectWorkerId == null) {
            throw new MitigationRuleException("A WORKER report requires a subject worker");
        }
        if (!scope.allowsIndividualData() && subjectWorkerId != null) {
            throw new MitigationRuleException(
                    "A PLANT_ZONE report cannot identify a worker (RF32, RNF11)");
        }
        this.evidenceDigest = EvidenceDigest.of(canonicalContent());
    }

    /**
     * Comprueba que el contenido guardado sigue produciendo el digest sellado.
     * Es lo que permite demostrar ante una auditoría que el reporte no se tocó
     * después de generarse.
     */
    public boolean verifyEvidence() {
        return evidenceDigest != null && evidenceDigest.matches(canonicalContent());
    }

    /**
     * Anota dónde quedó el archivo exportado. Idempotente y sin mover la marca
     * original: reexportar el mismo reporte no reescribe cuándo se exportó por
     * primera vez.
     */
    public void markExported(String reference) {
        if (exportedAt != null) return;
        this.fileReference = reference;
        this.exportedAt = LocalDateTime.now();
    }

    public boolean isExported() {
        return exportedAt != null;
    }

    /**
     * Cuántas entradas no tienen ninguna referencia de origen. QAS-07 exige que
     * sea cero: el 100 % de las cifras debe poder contrastarse.
     */
    public long entriesWithoutSource() {
        return entries.stream().filter(e -> !e.hasSourceReference()).count();
    }

    public boolean isFullyTraceable() {
        return entriesWithoutSource() == 0;
    }

    /**
     * El contenido canónico del que sale el digest.
     *
     * Se construye desde los datos del reporte y no desde el PDF: así el mismo
     * reporte da la misma huella aunque se cambie la plantilla de exportación, y
     * el digest sigue sirviendo para detectar una manipulación de los datos.
     */
    private String canonicalContent() {
        StringBuilder sb = new StringBuilder();
        sb.append(organizationId).append('|')
                .append(scope).append('|')
                .append(subjectWorkerId).append('|')
                .append(period).append('|')
                .append(version).append('\n');
        entries.forEach(e -> sb.append(e.canonicalForm()).append('\n'));
        return sb.toString();
    }
}
