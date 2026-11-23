package com.emsafe.bdd.steps;

import com.emsafe.devicemanagement.application.DeviceActor;
import com.emsafe.devicemanagement.application.DeviceManagementService;
import com.emsafe.devicemanagement.application.ports.DeviceConfigPublisher;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.AuditEntryView;
import com.emsafe.devicemanagement.application.views.DeviceManagementViews.DeviceConfigView;
import com.emsafe.devicemanagement.infrastructure.mqtt.RecordingMqttGateway;
import com.emsafe.devicemanagement.infrastructure.outbox.DeviceManagementOutboxRelay;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceManagementEventJpaRepository;
import com.emsafe.devicemanagement.infrastructure.seed.DeviceManagementDemoSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.es.Cuando;
import io.cucumber.java.es.Dado;
import io.cucumber.java.es.Entonces;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class DeviceConfigSteps {

    @Autowired SolicitudSteps solicitud;
    @Autowired DeviceManagementService deviceManagement;
    @Autowired RecordingMqttGateway mqtt;
    @Autowired DeviceManagementEventJpaRepository outboxEvents;
    @Autowired ApplicationEventPublisher events;
    @Autowired ObjectProvider<DeviceConfigPublisher> configPublisher;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;

    private String deviceId;
    private BigDecimal umbralAnterior;

    @Dado("el dosímetro {string} de mi organización con la configuración en la versión {int}")
    public void elDosimetroConLaConfiguracionEnLaVersion(String deviceId, int version) {
        assertThat(deviceId).isEqualTo(DeviceManagementDemoSeeder.DOSIMETER);
        this.deviceId = deviceId;
        // La semilla lo deja en la versión 1; cada cambio de umbral suma una versión.
        int actual = config().configVersion();
        assertThat(actual).as("versión inicial del dosímetro").isLessThanOrEqualTo(version);
        for (int v = actual; v < version; v++) {
            deviceManagement.changeAlertThreshold(sso(), deviceId, BigDecimal.valueOf(240 + 10L * v));
        }
        DeviceConfigView config = config();
        assertThat(config.configVersion()).isEqualTo(version);
        umbralAnterior = config.alertThresholdUT();
        relay().drainOnce();
        mqtt.clear();
    }

    @Cuando("defino un umbral de {int} µT")
    public void definoUnUmbral(int umbral) {
        solicitud.intentar(() -> deviceManagement.changeAlertThreshold(sso(), deviceId, BigDecimal.valueOf(umbral)));
    }

    @Entonces("la configuración del dosímetro pasa a la versión {int} con un umbral de {int} µT")
    public void laConfiguracionPasaALaVersion(int version, int umbral) {
        assertThat(solicitud.rechazo()).isNull();
        DeviceConfigView config = config();
        assertThat(config.configVersion()).isEqualTo(version);
        assertThat(config.alertThresholdUT()).isEqualByComparingTo(BigDecimal.valueOf(umbral));
        assertThat(config.definedBy()).isEqualTo("SSO_RESPONSIBLE");
    }

    @Entonces("se registra una entrada de auditoría con el valor anterior y el nuevo")
    public void seRegistraUnaEntradaDeAuditoria() {
        BigDecimal nuevo = config().alertThresholdUT();
        List<AuditEntryView> cambios = deviceManagement.audit(sso(), deviceId, 0, 50).content().stream()
                .filter(e -> "CONFIG_CHANGED".equals(e.entryType()) && "alertThresholdUT".equals(e.field()))
                .filter(e -> new BigDecimal(e.newValue()).compareTo(nuevo) == 0)
                .toList();
        assertThat(cambios).singleElement().satisfies(e -> {
            assertThat(new BigDecimal(e.oldValue())).isEqualByComparingTo(umbralAnterior);
            assertThat(e.actor()).isEqualTo(SolicitudSteps.SSO_EMAIL);
        });
    }

    @Entonces("se publica la configuración retenida en el tópico {string}")
    public void sePublicaLaConfiguracionRetenida(String topico) throws IOException {
        relay().drainOnce();
        List<RecordingMqttGateway.Published> enTopico = mqtt.published.stream()
                .filter(p -> p.topic().equals(topico)).toList();
        assertThat(enTopico).as("publicaciones en " + topico).isNotEmpty();
        RecordingMqttGateway.Published ultima = enTopico.get(enTopico.size() - 1);
        assertThat(ultima.retained()).isTrue();
        assertThat(ultima.qos()).isEqualTo(1);
        DeviceConfigView config = config();
        JsonNode mensaje = json.readTree(ultima.payload());
        assertThat(mensaje.get("deviceId").asText()).isEqualTo(deviceId);
        assertThat(mensaje.get("configVersion").asInt()).isEqualTo(config.configVersion());
        assertThat(mensaje.get("alertThresholdUT").decimalValue()).isEqualByComparingTo(config.alertThresholdUT());
    }

    @Entonces("la configuración del dosímetro sigue en la versión {int}")
    public void laConfiguracionSigueEnLaVersion(int version) {
        DeviceConfigView config = config();
        assertThat(config.configVersion()).isEqualTo(version);
        assertThat(config.alertThresholdUT()).isEqualByComparingTo(umbralAnterior);
        relay().drainOnce();
        assertThat(mqtt.published).noneMatch(p -> p.topic().equals("iot/" + deviceId + "/config"));
    }

    private DeviceConfigView config() {
        return deviceManagement.config(sso(), deviceId);
    }

    private DeviceActor sso() {
        return solicitud.actor(SolicitudSteps.SSO_EMAIL);
    }

    /** El relay real del outbox; en el perfil de pruebas no corre programado. */
    private DeviceManagementOutboxRelay relay() {
        return new DeviceManagementOutboxRelay(outboxEvents, events, configPublisher, json, clock);
    }
}
