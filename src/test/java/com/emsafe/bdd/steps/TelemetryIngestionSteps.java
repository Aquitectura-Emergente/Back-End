package com.emsafe.bdd.steps;

import com.emsafe.devicemanagement.application.DeviceManagementService;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.TelemetryReadingView;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractFixtures;
import com.emsafe.devicemanagement.infrastructure.seed.DeviceManagementDemoSeeder;
import com.emsafe.devicemanagement.interfaces.mqtt.MqttMessageRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Las lecturas entran por el router MQTT real con el mensaje de telemetría del contrato. */
public class TelemetryIngestionSteps {
    private static final String DOSIMETRO = DeviceManagementDemoSeeder.DOSIMETER;

    @Autowired SolicitudSteps solicitud;
    @Autowired MqttMessageRouter router;
    @Autowired DeviceManagementService deviceManagement;
    @Autowired ObjectMapper json;

    private String deviceId = DOSIMETRO;
    private byte[] ultimoMensaje;
    private final List<Instant> capturas = new ArrayList<>();

    @Dado("una lectura del dispositivo {string} con secuencia {int} ya almacenada")
    public void unaLecturaYaAlmacenada(String deviceId, int secuencia) throws IOException {
        this.deviceId = deviceId;
        Instant capturada = ahora().minusSeconds(5);
        ultimoMensaje = mensaje(List.of(lectura(secuencia, capturada, 219.5, "REALTIME", "VALID")));
        llegaPorMqtt(ultimoMensaje);
        assertThat(lecturasConSecuencia(secuencia)).hasSize(1);
    }

    @Cuando("la misma lectura llega otra vez por MQTT")
    public void laMismaLecturaLlegaOtraVez() {
        llegaPorMqtt(ultimoMensaje);
    }

    @Entonces("el dispositivo tiene una sola lectura con secuencia {int}")
    public void unaSolaLecturaConSecuencia(int secuencia) {
        assertThat(lecturasConSecuencia(secuencia)).hasSize(1);
    }

    @Cuando("llega una lectura de {int} µT con calidad {string}")
    public void llegaUnaLecturaConCalidad(int valor, String calidad) throws IOException {
        llegaPorMqtt(mensaje(List.of(lectura(500, ahora().minusSeconds(3), valor, "REALTIME", calidad))));
    }

    @Entonces("la lectura se almacena con calidad {string} y un valor de {int} µT")
    public void laLecturaSeAlmacenaConCalidad(String calidad, int valor) {
        assertThat(lecturas()).singleElement().satisfies(r -> {
            assertThat(r.quality()).isEqualTo(calidad);
            assertThat(r.magneticFluxDensityUT()).isEqualByComparingTo(BigDecimal.valueOf(valor));
        });
    }

    @Cuando("llegan {int} lecturas con origen {string}")
    public void lleganLecturasConOrigen(int cantidad, String origen) throws IOException {
        // Buffer del dosímetro: capturas de hace un rato, una por segundo.
        Instant primera = ahora().minusSeconds(cantidad + 10L);
        List<ObjectNode> lecturas = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            Instant capturada = primera.plusSeconds(i);
            capturas.add(capturada);
            lecturas.add(lectura(1000 + i, capturada, 150 + i, origen, "VALID"));
        }
        llegaPorMqtt(mensaje(lecturas));
    }

    @Entonces("se almacenan las {int} lecturas con la hora de captura del dosímetro")
    public void seAlmacenanConLaHoraDeCaptura(int cantidad) {
        List<TelemetryReadingView> guardadas = lecturas();
        assertThat(guardadas).hasSize(cantidad);
        assertThat(guardadas).allSatisfy(r -> {
            assertThat(r.origin()).isEqualTo("DEFERRED");
            assertThat(r.capturedAtSource()).isEqualTo("DEVICE_CLOCK");
            assertThat(r.capturedAt()).isBefore(r.receivedAt());
        });
        assertThat(guardadas).extracting(TelemetryReadingView::capturedAt)
                .containsExactlyInAnyOrderElementsOf(capturas);
    }

    private void llegaPorMqtt(byte[] payload) {
        router.route("iot/" + deviceId + "/telemetry", payload);
    }

    private List<TelemetryReadingView> lecturas() {
        return deviceManagement.telemetry(solicitud.actor(SolicitudSteps.SSO_EMAIL), deviceId, null, 500);
    }

    private List<TelemetryReadingView> lecturasConSecuencia(long secuencia) {
        return lecturas().stream().filter(r -> r.sequence() == secuencia).toList();
    }

    /** Mensaje de ejemplo del contrato con otras lecturas y un messageId nuevo. */
    private byte[] mensaje(List<ObjectNode> lecturas) throws IOException {
        ObjectNode mensaje = (ObjectNode) json.readTree(ContractFixtures.industrial("telemetry"));
        mensaje.put("messageId", UUID.randomUUID().toString());
        mensaje.put("deviceId", deviceId);
        mensaje.put("publishedAt", ahora().toString());
        ArrayNode readings = mensaje.putArray("readings");
        lecturas.forEach(readings::add);
        return json.writeValueAsBytes(mensaje);
    }

    private ObjectNode lectura(long secuencia, Instant capturada, double valor, String origen, String calidad) {
        ObjectNode r = json.createObjectNode();
        r.put("sequence", secuencia);
        r.put("capturedAt", capturada.toString());
        r.put("capturedAtSource", "DEVICE_CLOCK");
        r.put("receivedAt", "DEFERRED".equals(origen) ? ahora().toString() : capturada.toString());
        r.put("magneticFluxDensityUT", valor);
        r.put("origin", origen);
        r.put("quality", calidad);
        return r;
    }

    private static Instant ahora() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
