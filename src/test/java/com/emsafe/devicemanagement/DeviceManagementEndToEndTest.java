package com.emsafe.devicemanagement;

import com.emsafe.clientportal.application.ClientService;
import com.emsafe.clientportal.interfaces.rest.dto.ClientReadingDto;
import com.emsafe.devicemanagement.application.TelemetryService;
import com.emsafe.devicemanagement.interfaces.rest.dto.ReadingDto;
import com.emsafe.devicemanagement.domain.model.ConnectivityStatus;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.model.EdgeSegment;
import com.emsafe.devicemanagement.domain.model.OrganizationCode;
import com.emsafe.devicemanagement.domain.model.PlugCommandStatus;
import com.emsafe.devicemanagement.domain.model.ReadingRouting;
import com.emsafe.devicemanagement.domain.model.TelemetryReading;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.domain.repositories.EdgeNodeRepository;
import com.emsafe.devicemanagement.domain.repositories.OrganizationCodeRepository;
import com.emsafe.devicemanagement.domain.repositories.PlugCommandRepository;
import com.emsafe.devicemanagement.domain.repositories.TelemetryReadingRepository;
import com.emsafe.devicemanagement.infrastructure.mqtt.RecordingMqttGateway;
import com.emsafe.devicemanagement.infrastructure.seed.DeviceManagementDemoSeeder;
import com.emsafe.devicemanagement.interfaces.mqtt.MqttMessageRouter;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.personaldosimetry.infrastructure.persistence.ExposureSessionRepository;
import com.emsafe.personaldosimetry.infrastructure.persistence.PersonalReadingRepository;
import com.emsafe.plantconfiguration.application.PlantEdgeService;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantEdgeDto;
import com.emsafe.radiationmonitoring.application.RadiationReadingService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractFixtures.home;
import static com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractFixtures.industrial;
import static com.emsafe.devicemanagement.infrastructure.seed.DeviceManagementDemoSeeder.DOSIMETER;
import static com.emsafe.devicemanagement.infrastructure.seed.DeviceManagementDemoSeeder.HOME_EDGE;
import static com.emsafe.devicemanagement.infrastructure.seed.DeviceManagementDemoSeeder.PLANT_EDGE;
import static com.emsafe.devicemanagement.infrastructure.seed.DeviceManagementDemoSeeder.PLUG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo de punta a punta sin broker: con los datos de demo sembrados, los
 * mensajes de ejemplo de los README de los dos Edges (solo con la hora actual,
 * y el hogar con {@code home-demo}) recorren la ingesta completa.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dmendtoend;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "emsafe.device-management.seed-demo-data=true",
        "emsafe.device-management.seed-demo-session=true",
        "emsafe.device-management.allow-device-lending=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeviceManagementEndToEndTest {

    @TestConfiguration
    static class Broker {
        @Bean
        @Primary
        RecordingMqttGateway recordingMqttGateway() {
            return new RecordingMqttGateway();
        }
    }

    @Autowired DeviceManagementDemoSeeder seeder;
    @Autowired MqttMessageRouter router;
    @Autowired OrganizationCodeRepository codes;
    @Autowired UserRepository users;
    @Autowired DeviceRepository devices;
    @Autowired EdgeNodeRepository edges;
    @Autowired TelemetryReadingRepository readings;
    @Autowired PlugCommandRepository commands;
    @Autowired PlantEdgeService plantEdges;
    @Autowired RadiationReadingService radiation;
    @Autowired PersonalReadingRepository personalReadings;
    @Autowired ExposureSessionRepository sessions;
    @Autowired ClientService clients;
    @Autowired RecordingMqttGateway mqtt;
    @Autowired TelemetryService telemetry;

    @Test
    @Order(1)
    void theDemoIdentifiersExistAndSeedingTwiceChangesNothing() {
        AppUser company = users.findByEmail(DeviceManagementDemoSeeder.COMPANY_EMAIL).orElseThrow();
        AppUser house = users.findByEmail(DeviceManagementDemoSeeder.HOME_EMAIL).orElseThrow();
        assertThat(codes.resolve(new OrganizationCode("org-demo"))).contains(company.getId());
        assertThat(codes.resolve(new OrganizationCode("home-demo"))).contains(house.getId());
        Device dosimeter = devices.findBySerial(DOSIMETER).orElseThrow();
        assertThat(dosimeter.getDeviceType()).isEqualTo(DeviceType.PERSONAL_DOSIMETER);
        assertThat(dosimeter.organizationId()).isEqualTo(company.getId());
        assertThat(devices.findBySerial(PLUG).orElseThrow().organizationId()).isEqualTo(house.getId());
        assertThat(edges.findByEdgeNodeId(PLANT_EDGE).orElseThrow().getSegment()).isEqualTo(EdgeSegment.PLANT);
        assertThat(edges.findByEdgeNodeId(HOME_EDGE).orElseThrow().getOrganizationId()).isEqualTo(house.getId());
        assertThat(plantEdges.listFor(company.getId())).extracting(PlantEdgeDto::serialNumber).contains(PLANT_EDGE);
        assertThat(sessions.findSessionIdsCovering(dosimeter.getId(), Instant.now(),
                org.springframework.data.domain.PageRequest.of(0, 5))).hasSize(1);

        long deviceCount = devices.findAll().size();
        long edgeCount = edges.findAll().size();
        seeder.seed();
        assertThat(devices.findAll()).hasSize((int) deviceCount);
        assertThat(edges.findAll()).hasSize((int) edgeCount);
        assertThat(sessions.findSessionIdsCovering(dosimeter.getId(), Instant.now(),
                org.springframework.data.domain.PageRequest.of(0, 5))).hasSize(1);
    }

    @Test
    @Order(2)
    void theIndustrialEdgeExampleLandsInTheDemoSession() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        router.route("iot/" + DOSIMETER + "/telemetry", bytes(withTime(industrial("telemetry"), now.minusSeconds(2))));
        String saturated = withTime(industrial("telemetry"), now.minusSeconds(1))
                .replace("\"sequence\": 1234", "\"sequence\": 1235")
                .replace("\"quality\": \"VALID\"", "\"quality\": \"SATURATED\"");
        router.route("iot/" + DOSIMETER + "/telemetry", bytes(saturated));

        assertThat(readings.findByDevice(DOSIMETER, ReadingRouting.ASSIGNED_TO_SESSION, 10)).hasSize(2);
        TelemetryReading first = readings.findByDevice(DOSIMETER, null, 10).get(1);
        String sessionId = first.getRoutingRef();
        assertThat(personalReadings.count()).isGreaterThanOrEqualTo(2);
        assertThat(sessions.findById(sessionId).orElseThrow().getOutOfRangeReadings()).isEqualTo(1);

        router.route("edge/" + PLANT_EDGE + "/status", bytes(industrial("edge-online")));
        AppUser company = users.findByEmail(DeviceManagementDemoSeeder.COMPANY_EMAIL).orElseThrow();
        assertThat(plantEdges.listFor(company.getId())).filteredOn(e -> PLANT_EDGE.equals(e.serialNumber()))
                .singleElement().satisfies(e -> assertThat(e.connectivity()).isEqualTo("ONLINE"));
    }

    @Test
    @Order(3)
    void theHomeEdgeExampleWithHomeDemoGoesToTheHomeFlowAndTheDefaultOrganizationIsRefused() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String withDefaultOrg = withTime(home("telemetry"), now).replace("\"sequence\": 1234", "\"sequence\": 1300");
        router.route("iot/" + DOSIMETER + "/telemetry", bytes(withDefaultOrg));
        assertThat(readings.findByDevice(DOSIMETER, ReadingRouting.HOME_RECORDED, 10)).isEmpty();

        router.route("iot/" + DOSIMETER + "/telemetry", bytes(withDefaultOrg.replace("org-demo", "home-demo")));
        assertThat(readings.findByDevice(DOSIMETER, ReadingRouting.HOME_RECORDED, 10)).hasSize(1);
        var recorded = radiation.latest(DOSIMETER).orElseThrow();
        assertThat(recorded.level()).isEqualTo("DANGER");
        assertThat(recorded.value()).isEqualTo(219.5);

        router.route("iot/" + DOSIMETER + "/status", bytes(home("device-status").replace("2026-11-12T15:00:00Z",
                now.toString())));
        assertThat(devices.findBySerial(DOSIMETER).orElseThrow().getConnectivityEdgeNodeId()).isEqualTo(HOME_EDGE);
        assertThat(devices.findBySerial(DOSIMETER).orElseThrow().getConnectivity()).isEqualTo(ConnectivityStatus.ONLINE);
    }

    @Test
    @Order(4)
    void theAppOrdersThePlugAndTheEdgeStateConfirmsIt() {
        AppUser house = users.findByEmail(DeviceManagementDemoSeeder.HOME_EMAIL).orElseThrow();
        Device plug = devices.findBySerial(PLUG).orElseThrow();
        mqtt.clear();

        var dto = clients.setDesiredPlug(house.getId(), plug.getId(), "OFF");
        assertThat(dto.desiredPlug()).isEqualTo("OFF");
        RecordingMqttGateway.Published published = mqtt.published.get(0);
        assertThat(published.topic()).isEqualTo("iot/" + PLUG + "/command");
        String commandId = commands.findLatestForPlug(PLUG).orElseThrow().getCommandId();
        assertThat(new String(published.payload(), StandardCharsets.UTF_8)).contains(commandId);

        String state = home("plug-state").replace("3b8e1f7a-2c4d-4e9b-8a1f-6d5c4b3a2e10", commandId)
                .replace("2026-11-12T15:10:01Z", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        router.route("iot/" + PLUG + "/state", bytes(state));
        assertThat(commands.findByCommandId(commandId).orElseThrow().getStatus()).isEqualTo(PlugCommandStatus.CONFIRMED);
        assertThat(clients.getDevices(house.getId())).filteredOn(d -> PLUG.equals(d.serialNumber()))
                .singleElement().satisfies(d -> {
                    assertThat(d.plug()).isEqualTo("OFF");
                    assertThat(d.desiredPlug()).isEqualTo("OFF");
                });
    }

    /**
     * El dosímetro es de Quantum pero está prestado al hogar: la lectura del
     * hogar es del cliente home-demo en el historial REST, igual que en el
     * WebSocket, y no aparece en el historial de Quantum.
     */
    @Test
    @Order(5)
    void theHomeReadingOfALentDeviceBelongsToTheHomeClientAndNotToTheDeviceOwner() {
        AppUser company = users.findByEmail(DeviceManagementDemoSeeder.COMPANY_EMAIL).orElseThrow();
        AppUser house = users.findByEmail(DeviceManagementDemoSeeder.HOME_EMAIL).orElseThrow();
        Device dosimeter = devices.findBySerial(DOSIMETER).orElseThrow();
        Long homeReadingId = Long.valueOf(readings.findByDevice(DOSIMETER, ReadingRouting.HOME_RECORDED, 10)
                .get(0).getRoutingRef());

        assertThat(clients.getReadings(house.getId())).extracting(ClientReadingDto::id).contains(homeReadingId);
        assertThat(clients.getReadings(company.getId())).extracting(ClientReadingDto::id)
                .doesNotContain(homeReadingId);
        assertThat(clients.getDeviceReadings(company.getId(), dosimeter.getId())).extracting(ClientReadingDto::id)
                .doesNotContain(homeReadingId);

        ReadingDto published = telemetry.list(DOSIMETER, 50).stream()
                .filter(r -> homeReadingId.equals(r.getId())).findFirst().orElseThrow();
        assertThat(published.getClientId()).isEqualTo(house.getId());
    }

    private static String withTime(String json, Instant at) {
        return json.replace("2026-11-12T15:04:04Z", at.toString()).replace("2026-11-12T15:04:05Z", at.toString());
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
