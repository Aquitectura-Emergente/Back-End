package com.emsafe.bdd.steps;

import com.emsafe.devicemanagement.application.DeviceActor;
import com.emsafe.devicemanagement.application.PlugCommandService;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.PlugCommandView;
import com.emsafe.devicemanagement.infrastructure.mqtt.RecordingMqttGateway;
import com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractFixtures;
import com.emsafe.devicemanagement.infrastructure.seed.DeviceManagementDemoSeeder;
import com.emsafe.devicemanagement.interfaces.mqtt.MqttMessageRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PlugCommandSteps {

    @Autowired SolicitudSteps solicitud;
    @Autowired PlugCommandService plugCommands;
    @Autowired MqttMessageRouter router;
    @Autowired RecordingMqttGateway mqtt;
    @Autowired ObjectMapper json;

    private String plugId;
    private String usuario;
    private PlugCommandView orden;
    private String estadoReportado;

    @Dado("el enchufe {string} de mi vivienda")
    public void elEnchufeDeMiVivienda(String plugId) {
        assertThat(plugId).isEqualTo(DeviceManagementDemoSeeder.PLUG);
        this.plugId = plugId;
        this.usuario = DeviceManagementDemoSeeder.HOME_EMAIL;
        mqtt.clear();
    }

    @Dado("el enchufe {string} de otro cliente")
    public void elEnchufeDeOtroCliente(String plugId) {
        assertThat(plugId).isEqualTo(DeviceManagementDemoSeeder.PLUG);
        this.plugId = plugId;
        // El enchufe es del hogar demo; quien ordena es otra cuenta cliente.
        this.usuario = SolicitudSteps.OTRO_CLIENTE_EMAIL;
        assertThat(actor().client()).isTrue();
        mqtt.clear();
    }

    @Cuando("ordeno apagar el enchufe")
    public void ordenoApagarElEnchufe() {
        solicitud.intentar(() -> orden = plugCommands.request(actor(), plugId, "OFF"));
    }

    @Entonces("la orden queda en estado {string}")
    public void laOrdenQuedaEnEstado(String estado) {
        assertThat(solicitud.rechazo()).isNull();
        assertThat(estadoDeLaOrden()).isEqualTo(estado);
    }

    @Entonces("se publica en el tópico {string}")
    public void sePublicaEnElTopico(String topico) throws IOException {
        List<RecordingMqttGateway.Published> enTopico = mqtt.published.stream()
                .filter(p -> p.topic().equals(topico)).toList();
        assertThat(enTopico).singleElement().satisfies(p -> {
            assertThat(p.qos()).isEqualTo(1);
            assertThat(p.retained()).isFalse();
        });
        JsonNode mensaje = json.readTree(enTopico.get(0).payload());
        assertThat(mensaje.get("commandId").asText()).isEqualTo(orden.commandId());
        assertThat(mensaje.get("plugId").asText()).isEqualTo(plugId);
        assertThat(mensaje.get("action").asText()).isEqualTo("OFF");
        assertThat(mensaje.get("requestedBy").asText()).isEqualTo("USER");
    }

    @Cuando("el Edge publica el estado {string} con el mismo commandId")
    public void elEdgePublicaElEstado(String estado) throws IOException {
        ObjectNode mensaje = (ObjectNode) json.readTree(ContractFixtures.home("plug-state"));
        mensaje.put("plugId", plugId);
        mensaje.put("state", estado);
        estadoReportado = estado;
        mensaje.put("commandId", orden.commandId());
        mensaje.put("changedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        router.route("iot/" + plugId + "/state", json.writeValueAsBytes(mensaje));
    }

    @Entonces("la orden pasa al estado {string}")
    public void laOrdenPasaAlEstado(String estado) {
        assertThat(estadoDeLaOrden()).isEqualTo(estado);
        assertThat(plugCommands.state(actor(), plugId).state()).isEqualTo(estadoReportado);
    }

    private String estadoDeLaOrden() {
        return plugCommands.command(actor(), orden.commandId()).status();
    }

    private DeviceActor actor() {
        return solicitud.actor(usuario);
    }
}
