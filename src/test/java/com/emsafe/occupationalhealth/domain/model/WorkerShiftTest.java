package com.emsafe.occupationalhealth.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La invariante de WorkerShift: tramos contiguos y sin solape (informe §5.6.1).
 *
 * El informe la delega a una restricción EXCLUDE de PostgreSQL, que MySQL no
 * tiene. Aquí la protege el agregado, así que estos tests son la única red que
 * la sostiene — importan más de lo normal.
 */
class WorkerShiftTest {

    private static final Long ORG = 7L;
    private static final Long WORKER = 11L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 9, 8, 0);

    private static WorkerShift shiftStartedAt(LocalDateTime from) {
        return WorkerShift.start(ORG, WORKER, 100L, 200L, from);
    }

    @Test
    @DisplayName("un turno nuevo arranca activo con un único tramo abierto")
    void startsWithOneOpenAssignment() {
        WorkerShift shift = shiftStartedAt(START);

        assertTrue(shift.isActive());
        assertEquals(1, shift.getAssignments().size());
        assertTrue(shift.currentAssignment().isPresent());
        assertTrue(shift.currentAssignment().get().isOpen());
        assertEquals(200L, shift.currentZoneId().orElseThrow());
        assertEquals(START.toLocalDate(), shift.getShiftDate());
    }

    @Test
    @DisplayName("reasignar cierra el tramo vigente y abre el siguiente, sin solape")
    void reassignClosesPreviousAssignment() {
        WorkerShift shift = shiftStartedAt(START);
        LocalDateTime moment = START.plusHours(3);

        shift.reassign(101L, 201L, moment, 55L);

        assertEquals(2, shift.getAssignments().size());
        PositionAssignment first = shift.getAssignments().get(0);
        PositionAssignment second = shift.getAssignments().get(1);

        assertFalse(first.isOpen(), "el tramo anterior queda cerrado");
        assertEquals(moment, first.getInterval().getTo());
        assertEquals(moment, second.getInterval().getFrom(), "son contiguos");
        assertTrue(second.isOpen());
        assertTrue(shift.hasNonOverlappingAssignments());
        assertEquals(55L, second.getSourceActionId(), "queda claro qué medida lo originó");
    }

    @Test
    @DisplayName("tras varias reasignaciones sigue habiendo un solo tramo abierto")
    void onlyOneAssignmentStaysOpen() {
        WorkerShift shift = shiftStartedAt(START);
        shift.reassign(101L, 201L, START.plusHours(2), 1L);
        shift.reassign(102L, 202L, START.plusHours(4), 2L);
        shift.reassign(103L, 203L, START.plusHours(6), 3L);

        long open = shift.getAssignments().stream().filter(PositionAssignment::isOpen).count();
        assertEquals(1, open);
        assertEquals(203L, shift.currentZoneId().orElseThrow());
        assertTrue(shift.hasNonOverlappingAssignments());
    }

    @Test
    @DisplayName("reasignar hacia atrás se rechaza: reescribiría historia ya registrada")
    void cannotReassignBeforeCurrentStart() {
        WorkerShift shift = shiftStartedAt(START);
        shift.reassign(101L, 201L, START.plusHours(3), 1L);

        MitigationRuleException ex = assertThrows(MitigationRuleException.class,
                () -> shift.reassign(102L, 202L, START.plusHours(1), 2L));
        assertTrue(ex.getMessage().contains("Cannot reassign before"));
    }

    @Test
    @DisplayName("una reasignación debe decir qué medida la provocó")
    void reassignRequiresSourceAction() {
        WorkerShift shift = shiftStartedAt(START);

        assertThrows(MitigationRuleException.class,
                () -> shift.reassign(101L, 201L, START.plusHours(1), null));
    }

    @Test
    @DisplayName("cerrar el turno cierra su último tramo y es idempotente")
    void closeIsIdempotent() {
        WorkerShift shift = shiftStartedAt(START);
        LocalDateTime end = START.plusHours(8);

        shift.close(end);
        shift.close(end.plusHours(1));   // reintento

        assertEquals(ShiftState.CLOSED, shift.getState());
        assertEquals(end, shift.getAssignments().get(0).getInterval().getTo(),
                "la marca original no se mueve");
    }

    @Test
    @DisplayName("un turno cerrado no admite más asignaciones")
    void closedShiftRejectsAssignments() {
        WorkerShift shift = shiftStartedAt(START);
        shift.close(START.plusHours(8));

        MitigationRuleException ex = assertThrows(MitigationRuleException.class,
                () -> shift.reassign(101L, 201L, START.plusHours(9), 1L));
        assertTrue(ex.getMessage().contains("closed shift"));
    }

    @Test
    @DisplayName("una rotación no reinicia nada del acumulado: solo mueve de puesto")
    void reassignDoesNotTouchExposure() {
        WorkerShift shift = shiftStartedAt(START);
        shift.linkExposureSession("session-abc");

        shift.reassign(101L, 201L, START.plusHours(3), 1L);

        assertEquals("session-abc", shift.getExposureSessionId(),
                "la jornada de exposición sigue siendo la misma (regla 6)");
    }
}
