package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Tramo temporal de una asignación de puesto (informe §5.6.1).
 *
 * El informe define from y to obligatorios, pero el tramo vigente de un turno
 * no tiene fin todavía: aquí `to` nulo significa abierto. Cerrarlo es lo que le
 * da duración, y eso solo ocurre a través de PositionAssignment.closeAt().
 *
 * Se usa LocalDateTime y no Instant por coherencia con el resto del backend,
 * que fecha todo así (Alert.createdAt, WorkOrder.completedAt).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ShiftInterval {

    @Column(name = "from_at", nullable = false)
    private LocalDateTime from;

    @Column(name = "to_at")
    private LocalDateTime to;

    /** Abre un tramo sin fin definido. */
    public static ShiftInterval openAt(LocalDateTime from) {
        if (from == null) {
            throw new MitigationRuleException("Assignment start is required");
        }
        return new ShiftInterval(from, null);
    }

    public static ShiftInterval of(LocalDateTime from, LocalDateTime to) {
        return openAt(from).closedAt(to);
    }

    /**
     * Devuelve el mismo tramo con fin. La duración debe ser positiva: un tramo
     * que acaba antes de empezar, o que dura cero, no representa trabajo real.
     */
    public ShiftInterval closedAt(LocalDateTime end) {
        if (end == null) {
            throw new MitigationRuleException("Assignment end is required");
        }
        if (!end.isAfter(from)) {
            throw new MitigationRuleException(
                    "Assignment end must be after its start: " + from + " → " + end);
        }
        return new ShiftInterval(from, end);
    }

    public boolean isOpen() {
        return to == null;
    }

    /** Minutos del tramo, o los transcurridos hasta ahora si sigue abierto. */
    public long durationMinutes() {
        return Duration.between(from, to != null ? to : LocalDateTime.now()).toMinutes();
    }

    public boolean contains(LocalDateTime moment) {
        if (moment.isBefore(from)) return false;
        return to == null || moment.isBefore(to);
    }

    /**
     * Si dos tramos comparten algún instante. Dos tramos abiertos siempre se
     * solapan, que es justo el caso que WorkerShift tiene que impedir.
     */
    public boolean overlaps(ShiftInterval other) {
        boolean endsBeforeOtherStarts = to != null && !to.isAfter(other.from);
        boolean startsAfterOtherEnds = other.to != null && !from.isBefore(other.to);
        return !endsBeforeOtherStarts && !startsAfterOtherEnds;
    }
}
