package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * El turno de un operario y la secuencia de puestos que ocupa (informe §5.6.1).
 *
 * Protege una invariante: los tramos son contiguos y no se solapan. El informe
 * la delega a una restricción EXCLUDE de PostgreSQL, que MySQL no tiene, así
 * que aquí la protege el agregado — reasignar cierra el tramo vigente antes de
 * abrir el siguiente, y no hay forma de añadir un tramo salteándose ese paso.
 *
 * Una rotación NO reinicia la exposición acumulada (regla 6): este agregado
 * solo mueve al operario de puesto y publica el hecho; el acumulado lo conserva
 * Personal Dosimetry, que aplica el límite del puesto nuevo.
 */
@Entity
@Table(name = "worker_shifts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkerShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "worker_id", nullable = false)
    private Long workerId;

    /** Jornada de exposición de Personal Dosimetry asociada al turno. */
    @Column(name = "exposure_session_id", length = 64)
    private String exposureSessionId;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShiftState state = ShiftState.ACTIVE;

    @OneToMany(mappedBy = "workerShift",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 30)
    @Builder.Default
    private List<PositionAssignment> assignments = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Bloqueo optimista. El informe lo pide para que dos aprobaciones
     * simultáneas no dejen el turno a medias: la segunda falla y se reintenta.
     */
    @Version
    private Long version;

    // ─── Comportamiento del agregado ──────────────────────────────────────────

    /** Abre un turno nuevo con su primer tramo ya asignado. */
    public static WorkerShift start(Long organizationId, Long workerId, Long jobPositionId,
                                    Long plantZoneId, LocalDateTime from) {
        WorkerShift shift = WorkerShift.builder()
                .organizationId(organizationId)
                .workerId(workerId)
                .shiftDate(from.toLocalDate())
                .state(ShiftState.ACTIVE)
                .build();
        shift.startAssignment(jobPositionId, plantZoneId, from, null);
        return shift;
    }

    /**
     * Abre un tramo. Si ya había uno vigente lo cierra en el mismo instante, de
     * modo que la secuencia queda contigua y sin solape por construcción.
     *
     * El instante no puede ser anterior al inicio del tramo vigente: reasignar
     * hacia atrás reescribiría historia ya registrada.
     */
    public void startAssignment(Long jobPositionId, Long plantZoneId,
                                LocalDateTime from, Long sourceActionId) {
        if (state == ShiftState.CLOSED) {
            throw new MitigationRuleException("Cannot assign a position on a closed shift");
        }
        currentAssignment().ifPresent(open -> {
            if (from.isBefore(open.getInterval().getFrom())) {
                throw new MitigationRuleException(
                        "Cannot reassign before the current assignment started: "
                                + open.getInterval().getFrom() + " → " + from);
            }
            open.closeAt(from);
        });
        assignments.add(PositionAssignment.builder()
                .organizationId(organizationId)
                .workerShift(this)
                .jobPositionId(jobPositionId)
                .plantZoneId(plantZoneId)
                .interval(ShiftInterval.openAt(from))
                .sourceActionId(sourceActionId)
                .build());
    }

    /**
     * Mueve al operario a otro puesto y zona por efecto de una medida aprobada.
     * Es el mismo mecanismo que startAssignment, nombrado como lo nombra el
     * informe, y deja constancia de qué acción lo provocó.
     */
    public void reassign(Long jobPositionId, Long plantZoneId,
                         LocalDateTime effectiveAt, Long sourceActionId) {
        if (sourceActionId == null) {
            throw new MitigationRuleException("A reassignment must reference the action that caused it");
        }
        startAssignment(jobPositionId, plantZoneId, effectiveAt, sourceActionId);
    }

    /** El tramo vigente: el único que sigue abierto. */
    public Optional<PositionAssignment> currentAssignment() {
        return assignments.stream()
                .filter(PositionAssignment::isOpen)
                .max(Comparator.comparing(a -> a.getInterval().getFrom()));
    }

    /** La zona en la que está el operario ahora, si el turno sigue abierto. */
    public Optional<Long> currentZoneId() {
        return currentAssignment().map(PositionAssignment::getPlantZoneId);
    }

    /**
     * Cierra el turno y su último tramo. Idempotente: cerrar dos veces no mueve
     * las marcas originales.
     */
    public void close(LocalDateTime moment) {
        if (state == ShiftState.CLOSED) return;
        currentAssignment().ifPresent(open -> open.closeAt(moment));
        this.state = ShiftState.CLOSED;
    }

    /** Vincula la jornada de exposición que Dosimetry asoció a este turno. */
    public void linkExposureSession(String sessionId) {
        this.exposureSessionId = sessionId;
    }

    public boolean isActive() {
        return state == ShiftState.ACTIVE;
    }

    /**
     * Comprobación de la invariante, para los tests y para no confiar solo en
     * que los métodos de arriba se usen bien.
     */
    public boolean hasNonOverlappingAssignments() {
        List<PositionAssignment> ordered = assignments.stream()
                .sorted(Comparator.comparing(a -> a.getInterval().getFrom()))
                .toList();
        for (int i = 0; i < ordered.size() - 1; i++) {
            if (ordered.get(i).getInterval().overlaps(ordered.get(i + 1).getInterval())) {
                return false;
            }
        }
        return true;
    }
}
