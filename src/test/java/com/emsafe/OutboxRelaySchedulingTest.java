package com.emsafe;

import com.emsafe.aiforecasting.infrastructure.ForecastingOutboxRelay;
import com.emsafe.personaldosimetry.infrastructure.DosimetryOutboxRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El cableado de los dos relays, que es lo único de ellos que los tests de
 * integración no ejercitan: allí se llama a {@code drainOnce()}, que es
 * determinista, y el {@code @Scheduled} no se dispara nunca.
 *
 * <p>No se comprueba que el scheduler <em>dispare</em> —eso exigiría esperar un
 * reloj real y sería un test lento y escamoso—, sino las tres cosas que pueden
 * romperse en silencio al tocar la configuración.</p>
 *
 * <h2>Por qué la primera es la que importa</h2>
 *
 * <p>Los relays se apagan en el perfil de test <b>por nombre de propiedad</b>. Si
 * alguien renombra la propiedad en la anotación y no en
 * {@code application-test.properties}, el apagado deja de aplicar: los
 * {@code @Scheduled} vuelven a dispararse en medio de los asserts y entregan
 * eventos que los tests no esperan. El fallo aparecería lejos de la causa y de
 * forma intermitente. Esta comprobación ata los dos lados.</p>
 */
class OutboxRelaySchedulingTest {

    private static final Properties TEST_PROFILE = load();

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = OutboxRelaySchedulingTest.class
                .getResourceAsStream("/application-test.properties")) {
            assertNotNull(in, "application-test.properties existe para apagar los relays");
            properties.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return properties;
    }

    @Test
    @DisplayName("el perfil de test apaga los dos relays por el nombre que usan sus anotaciones")
    void theTestProfileSwitchesOffExactlyThePropertiesTheRelaysRead() {
        assertRelayIsSwitchedOffInTests(DosimetryOutboxRelay.class);
        assertRelayIsSwitchedOffInTests(ForecastingOutboxRelay.class);
    }

    private void assertRelayIsSwitchedOffInTests(Class<?> relay) {
        ConditionalOnProperty condition = relay.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, relay.getSimpleName() + " se puede apagar por configuración");
        assertEquals(1, condition.name().length);
        String property = condition.name()[0];

        assertEquals("false", TEST_PROFILE.getProperty(property),
                () -> property + " tiene que estar a false en application-test.properties: "
                        + "si no, el @Scheduled de " + relay.getSimpleName() + " dispara entre asserts");
        assertTrue(condition.matchIfMissing(),
                () -> relay.getSimpleName() + " va encendido sin configurar en dev, demo y prod");
        assertEquals("true", condition.havingValue());
    }

    @Test
    @DisplayName("el intervalo es configurable y trae un default, para no exigir configuracion en prod")
    void theDelayIsAPlaceholderWithADefault() throws Exception {
        for (Class<?> relay : new Class<?>[]{DosimetryOutboxRelay.class, ForecastingOutboxRelay.class}) {
            Scheduled scheduled = relay.getMethod("drainScheduled").getAnnotation(Scheduled.class);
            assertNotNull(scheduled, relay.getSimpleName() + ".drainScheduled está programado");
            String delay = scheduled.fixedDelayString();
            assertTrue(delay.startsWith("${") && delay.contains(":") && delay.endsWith("}"),
                    () -> relay.getSimpleName() + " usa un placeholder con default, no un literal: " + delay);
        }
    }

    /**
     * Un fallo de una pasada no puede matar al scheduler: si la excepción saliera
     * de {@code drainScheduled}, Spring cancelaría la tarea y el outbox dejaría de
     * drenarse <b>para siempre</b>, sin que nada lo delatara hasta que alguien
     * mirara la tabla.
     */
    @Test
    @DisplayName("una pasada que falla no se lleva por delante al scheduler")
    void aFailingPassDoesNotPropagate() {
        var dosimetry = new DosimetryOutboxRelay(null, null, null, null) {
            @Override
            public int drainOnce() {
                throw new IllegalStateException("la base de datos no responde");
            }
        };
        var forecasting = new ForecastingOutboxRelay(null, null, null) {
            @Override
            public int drainOnce() {
                throw new IllegalStateException("la base de datos no responde");
            }
        };

        assertDoesNotThrow(dosimetry::drainScheduled);
        assertDoesNotThrow(forecasting::drainScheduled);
    }
}
