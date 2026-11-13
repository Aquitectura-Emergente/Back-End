package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.ForecastingTestContext;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.ShiftUpdated;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.emsafe.aiforecasting.application.ForecastingTestContext.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Traducción del evento de Occupational Health al comando del inbox.
 *
 * <p>Aquí se invoca el listener a mano: lo que se verifica es el mapeo y que un
 * fallo no escape. Que Spring lo llame tras el commit y en una transacción nueva
 * depende del cableado y se comprueba en {@code OccupationalHealthApiIntegrationTest}.</p>
 */
class ShiftUpdatedEventListenerTest {

    private final ForecastingTestContext ctx = new ForecastingTestContext();
    private final ShiftUpdatedEventListener listener = new ShiftUpdatedEventListener(ctx.shiftUpdated);

    private static ShiftUpdated relocation(String eventId, Long newZoneId, Long newPositionId) {
        EventEnvelope envelope = new EventEnvelope(
                eventId, 1, ORG, LocalDateTime.now(), "corr-" + eventId, null);
        return new ShiftUpdated(envelope, session(101L), 500L, 101L,
                10L, newPositionId, newZoneId, LocalDateTime.now(), 9L);
    }

    @Test
    void movesTheSnapshotToTheDestinationZoneAndPosition() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));

        listener.on(relocation("sh-1", 7L, 30L));

        WorkerExposureSnapshot s = ctx.snapshots.findBySessionId(session(101L)).orElseThrow();
        // La zona viaja como el id decimal de plant_zones, no como "Z-7".
        assertThat(s.zoneId()).isEqualTo("7");
        assertThat(s.positionId()).isEqualTo(30L);
        // El evento no habla del fin de turno, así que se conserva el que había.
        assertThat(s.plannedShiftEnd()).isEqualTo(at(16, 0));
        assertThat(s.accumulatedIndex()).isEqualByComparingTo("1000");
        assertThat(ctx.processed.isProcessed("sh-1")).isTrue();
    }

    @Test
    void carriesTheEnvelopeSoTheChainStaysTraceable() {
        ctx.seedHighScenario();
        ctx.trigger.handle(warningCmd("w-1", 101L, "7200"));
        int before = ctx.outbox.all().size();

        listener.on(relocation("sh-1", 7L, 30L));

        assertThat(ctx.outbox.all()).hasSizeGreaterThan(before);
        assertThat(ctx.outbox.all().subList(before, ctx.outbox.all().size()))
                .allSatisfy(e -> {
                    assertThat(e.causationId()).isEqualTo("sh-1");
                    assertThat(e.correlationId()).isEqualTo("corr-sh-1");
                });
    }

    @Test
    void aZonelessEventKeepsTheCurrentZone() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));

        listener.on(relocation("sh-1", null, 30L));

        assertThat(ctx.snapshots.findBySessionId(session(101L)).orElseThrow().zoneId()).isEqualTo(ZONE);
    }

    /**
     * Lo que hace esto importante: la reubicación ya está confirmada y no se
     * puede deshacer. Si el fallo escapara, el SSO vería un error después de una
     * acción que sí ocurrió.
     */
    @Test
    void doesNotPropagateWhenTheSessionIsUnknownToForecasting() {
        assertThatNoException().isThrownBy(() -> listener.on(relocation("sh-1", 7L, 30L)));
        assertThat(ctx.processed.isProcessed("sh-1")).isFalse();
    }

    @Test
    void aRepeatedEventIsIgnoredWithoutFailing() {
        ctx.upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "1000"));
        listener.on(relocation("sh-1", 7L, 30L));

        assertThatNoException().isThrownBy(() -> listener.on(relocation("sh-1", 99L, 40L)));

        WorkerExposureSnapshot s = ctx.snapshots.findBySessionId(session(101L)).orElseThrow();
        assertThat(s.zoneId()).isEqualTo("7");
        assertThat(s.positionId()).isEqualTo(30L);
    }
}
