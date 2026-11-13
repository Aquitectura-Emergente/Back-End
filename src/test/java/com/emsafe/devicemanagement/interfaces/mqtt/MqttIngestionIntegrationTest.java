package com.emsafe.devicemanagement.interfaces.mqtt;

import com.emsafe.devicemanagement.application.DeviceConnectivityService;
import com.emsafe.devicemanagement.application.PlugCommandService;
import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway;
import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway.DosimetryReading;
import com.emsafe.devicemanagement.application.ports.DosimetrySessionGateway.DosimetryResult;
import com.emsafe.devicemanagement.application.ports.HomeReadingGateway;
import com.emsafe.devicemanagement.application.ports.HomeReadingGateway.RecordedHomeReading;
import com.emsafe.devicemanagement.application.ports.HomeReadingNotifier;
import com.emsafe.devicemanagement.application.ports.PlantEdgeGateway;
import com.emsafe.devicemanagement.domain.model.AuditEntryType;
import com.emsafe.devicemanagement.domain.model.ConnectivityStatus;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.domain.model.DeviceAuditEntry;
import com.emsafe.devicemanagement.domain.model.DeviceSerial;
import com.emsafe.devicemanagement.domain.model.DeviceType;
import com.emsafe.devicemanagement.domain.model.EdgeNode;
import com.emsafe.devicemanagement.domain.model.EdgeSegment;
import com.emsafe.devicemanagement.domain.model.OrganizationCode;
import com.emsafe.devicemanagement.domain.model.PlugCommandStatus;
import com.emsafe.devicemanagement.domain.model.PlugPower;
import com.emsafe.devicemanagement.domain.model.ReadingQuality;
import com.emsafe.devicemanagement.domain.model.ReadingRouting;
import com.emsafe.devicemanagement.domain.model.SamplingInterval;
import com.emsafe.devicemanagement.domain.model.TelemetryReading;
import com.emsafe.devicemanagement.domain.repositories.DeviceRepository;
import com.emsafe.devicemanagement.domain.repositories.EdgeNodeRepository;
import com.emsafe.devicemanagement.domain.repositories.OrganizationCodeRepository;
import com.emsafe.devicemanagement.domain.repositories.PlugCommandRepository;
import com.emsafe.devicemanagement.domain.repositories.TelemetryReadingRepository;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceAuditEntryJpaRepository;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractFixtures.home;
import static com.emsafe.devicemanagement.infrastructure.mqtt.contract.ContractFixtures.industrial;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La ingesta de punta a punta desde el router MQTT, con los JSON de ejemplo de
 * los README. Personal Dosimetry, el hogar y Plant Configuration son dobles:
 * aquí se prueba qué les llega.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dmingestion;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "emsafe.device-management.allow-device-lending=true"
})
class MqttIngestionIntegrationTest {
    static final Instant CAPTURED = Instant.parse("2026-11-12T15:04:04Z");

    @Autowired MqttMessageRouter router;
    @Autowired UserRepository users;
    @Autowired DeviceRepository devices;
    @Autowired EdgeNodeRepository edges;
    @Autowired OrganizationCodeRepository codes;
    @Autowired TelemetryReadingRepository readings;
    @Autowired DeviceAuditEntryJpaRepository audit;
    @Autowired PlugCommandService plugCommands;
    @Autowired PlugCommandRepository commandRepository;
    @Autowired DeviceConnectivityService connectivity;

    @MockBean DosimetrySessionGateway dosimetry;
    @MockBean HomeReadingGateway homeReadings;
    @MockBean HomeReadingNotifier notifier;
    @MockBean PlantEdgeGateway plantEdges;

    String dos;
    String plug;
    String org;
    String homeOrg;
    String plantEdge;
    String homeEdge;
    AppUser company;
    AppUser house;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        dos = "DOS-" + s;
        plug = "PLUG-" + s;
        org = "org-" + s;
        homeOrg = "home-" + s;
        plantEdge = "EDGE-P-" + s;
        homeEdge = "EDGE-H-" + s;
        company = users.save(account("company-" + s));
        house = users.save(account("house-" + s));
        codes.assign(new OrganizationCode(org), company.getId());
        codes.assign(new OrganizationCode(homeOrg), house.getId());
        Instant now = Instant.parse("2026-11-12T14:00:00Z");
        edges.save(EdgeNode.register(plantEdge, company.getId(), EdgeSegment.PLANT, now));
        edges.save(EdgeNode.register(homeEdge, house.getId(), EdgeSegment.HOME, now));
        devices.save(Device.register(DeviceSerial.of(dos), DeviceType.PERSONAL_DOSIMETER, null, company, "t", now));
        devices.save(Device.register(DeviceSerial.of(plug), DeviceType.SMART_PLUG, null, house, "t", now));
        when(dosimetry.sessionCovering(any(), any())).thenReturn(Optional.of("session-1"));
        when(dosimetry.ingest(anyString(), any())).thenReturn(
                new DosimetryResult(DosimetryResult.Status.ACCEPTED, "reading-1", null));
        when(homeReadings.record(any(), any(), any(), anyBoolean(), any())).thenReturn(new RecordedHomeReading(
                99L, dos, 1L, "Sensor", 219.5, "DANGER", null, null, null, null, house.getId(), "House", null));
    }

    private static AppUser account(String name) {
        return AppUser.builder().email(name + "@test.local").passwordHash("x").name(name).initials("XX")
                .role(Role.CLIENT).status("active").clientType("company").build();
    }

    // ─── Telemetría del dosímetro ─────────────────────────────────────────────

    @Test
    void theIndustrialExampleReachesTheSessionWithItsWindowAndDerivedBoot() {
        sendIndustrial(1234, "2026-11-12T15:04:04Z", "VALID", "REALTIME");

        TelemetryReading stored = only();
        assertThat(stored.getRouting()).isEqualTo(ReadingRouting.ASSIGNED_TO_SESSION);
        assertThat(stored.getRoutingRef()).isEqualTo("session-1");
        assertThat(stored.getEdgeNodeId()).isEqualTo(plantEdge);
        assertThat(stored.getFluxUt()).isEqualByComparingTo("219.5");
        DosimetryReading sent = sentToDosimetry();
        assertThat(sent.capturedTo()).isEqualTo(CAPTURED);
        assertThat(sent.capturedFrom()).isEqualTo(CAPTURED.minusSeconds(1));
        assertThat(sent.sequence()).isEqualTo(1234);
        assertThat(sent.bootId()).isEqualTo(stored.getBootId()).isNotBlank();
        assertThat(sent.saturated()).isFalse();
        Device device = devices.findBySerial(dos).orElseThrow();
        assertThat(device.getConnectivity()).isEqualTo(ConnectivityStatus.ONLINE);
        assertThat(device.getLastSeenAt()).isEqualTo(CAPTURED);
    }

    @Test
    void aRetriedMessageIsStoredOnceAndDoesNotLookLikeAReboot() {
        sendIndustrial(1234, "2026-11-12T15:04:04Z", "VALID", "REALTIME");
        sendIndustrial(1235, "2026-11-12T15:04:05Z", "VALID", "REALTIME");
        String boot = devices.findBySerial(dos).orElseThrow().getBootId();
        sendIndustrial(1234, "2026-11-12T15:04:04Z", "VALID", "REALTIME");

        assertThat(readings.findByDevice(dos, null, 10)).hasSize(2);
        assertThat(devices.findBySerial(dos).orElseThrow().getBootId()).isEqualTo(boot);
        verify(dosimetry, times(2)).ingest(anyString(), any());
    }

    @Test
    void aLowerSequenceAfterARealRestartStartsANewBoot() {
        sendIndustrial(1234, "2026-11-12T15:04:04Z", "VALID", "REALTIME");
        sendIndustrial(3, "2026-11-12T15:10:00Z", "VALID", "REALTIME");
        List<TelemetryReading> rows = readings.findByDevice(dos, null, 10);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getBootId()).isNotEqualTo(rows.get(1).getBootId());
    }

    @Test
    void withoutASessionTheReadingIsKeptUnassigned() {
        when(dosimetry.sessionCovering(any(), any())).thenReturn(Optional.empty());
        sendIndustrial(1234, "2026-11-12T15:04:04Z", "VALID", "REALTIME");
        assertThat(only().getRouting()).isEqualTo(ReadingRouting.UNASSIGNED);
        verify(dosimetry, never()).ingest(anyString(), any());
    }

    @Test
    void aRejectionFromDosimetryIsKeptWithItsReason() {
        when(dosimetry.ingest(anyString(), any())).thenReturn(
                new DosimetryResult(DosimetryResult.Status.REJECTED, null, "Capture interval overlaps"));
        sendIndustrial(1234, "2026-11-12T15:04:04Z", "VALID", "REALTIME");
        TelemetryReading stored = only();
        assertThat(stored.getRouting()).isEqualTo(ReadingRouting.REJECTED_BY_TARGET);
        assertThat(stored.getRoutingDetail()).contains("overlaps");
    }

    @Test
    void theWindowUsesTheConfiguredSamplingIntervalAndDeferredReadingsTheirRealTime() {
        Device device = devices.findBySerial(dos).orElseThrow();
        device.changeOperationalConfig(new SamplingInterval(2000), null, "admin", Instant.now());
        devices.save(device);
        sendIndustrial(1234, "2026-11-12T09:00:00Z", "VALID", "DEFERRED");

        verify(dosimetry).sessionCovering(any(), eq(Instant.parse("2026-11-12T09:00:00Z")));
        DosimetryReading sent = sentToDosimetry();
        assertThat(sent.capturedFrom()).isEqualTo(Instant.parse("2026-11-12T08:59:58Z"));
        assertThat(only().getOrigin().name()).isEqualTo("DEFERRED");
    }

    @Test
    void aSaturatedReadingIsKeptAndSentAsAMinimum() {
        sendIndustrial(1234, "2026-11-12T15:04:04Z", "SATURATED", "REALTIME");
        assertThat(only().getQuality()).isEqualTo(ReadingQuality.SATURATED);
        assertThat(sentToDosimetry().saturated()).isTrue();
        assertThat(sentToDosimetry().fieldMicrotesla()).isEqualByComparingTo("219.5");
    }

    // ─── Telemetría del hogar y préstamo ──────────────────────────────────────

    @Test
    void theHomeExampleGoesToTheHomeFlowOfTheEdgeOwnerEvenOnALentDosimeter() {
        String json = home("telemetry").replace("EMSafe-DOS-01", dos).replace("org-demo", homeOrg)
                .replace("EDGE-HOGAR-01", homeEdge);
        router.route("iot/" + dos + "/telemetry", bytes(json));

        TelemetryReading stored = only();
        assertThat(stored.getRouting()).isEqualTo(ReadingRouting.HOME_RECORDED);
        assertThat(stored.getRoutingRef()).isEqualTo("99");
        assertThat(stored.getOrganizationId()).isEqualTo(house.getId());
        ArgumentCaptor<AppUser> owner = ArgumentCaptor.forClass(AppUser.class);
        verify(homeReadings).record(any(), owner.capture(), any(), eq(false), eq(CAPTURED));
        assertThat(owner.getValue().getId()).isEqualTo(house.getId());
        verify(notifier).notify(any(), eq(false));
        verify(dosimetry, never()).ingest(anyString(), any());
        assertThat(auditTypes()).contains(AuditEntryType.DEVICE_TYPE_MISMATCH, AuditEntryType.DEVICE_LENT);
    }

    @Test
    void aTypeMismatchWithinTheOwnerIsRoutedByTheMessageAndNotRejected() {
        String json = home("telemetry").replace("EMSafe-DOS-01", dos).replace("org-demo", org)
                .replace("EDGE-HOGAR-01", plantEdge);
        router.route("iot/" + dos + "/telemetry", bytes(json));
        assertThat(only().getRouting()).isEqualTo(ReadingRouting.HOME_RECORDED);
        assertThat(auditTypes()).contains(AuditEntryType.DEVICE_TYPE_MISMATCH)
                .doesNotContain(AuditEntryType.DEVICE_LENT);
    }

    @Test
    void messagesThatFailValidationStoreNothing() {
        String base = industrial("telemetry").replace("EMSafe-DOS-01", dos).replace("EDGE-PLANTA-01", plantEdge);
        router.route("iot/" + dos + "/telemetry", bytes(base.replace("org-demo", "org-unknown")));
        router.route("iot/" + dos + "/telemetry", bytes(base.replace("org-demo", homeOrg)));
        router.route("iot/" + dos + "/telemetry", bytes(base.replace("org-demo", org)
                .replace(plantEdge, homeEdge)));
        router.route("iot/OTHER/telemetry", bytes(base.replace("org-demo", org)));
        router.route("iot/" + dos + "/telemetry", bytes(base.replace("org-demo", org)
                .replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"2.0\"")));
        router.route("iot/" + dos + "/telemetry", bytes(base.replace("org-demo", org)
                .replace("PERSONAL_DOSIMETER", "WEARABLE")));
        String unknownDevice = base.replace("org-demo", org).replace(dos, "NOPE-" + dos);
        router.route("iot/NOPE-" + dos + "/telemetry", bytes(unknownDevice));
        assertThat(readings.findByDevice(dos, null, 10)).isEmpty();
        assertThat(readings.findByDevice("NOPE-" + dos, null, 10)).isEmpty();
    }

    // ─── Estados ──────────────────────────────────────────────────────────────

    @Test
    void theDeviceStatusChangesConnectivityAndIgnoresOlderStates() {
        String online = industrial("device-status").replace("EMSafe-DOS-01", dos).replace("EDGE-PLANTA-01", plantEdge);
        router.route("iot/" + dos + "/status", bytes(online));
        String older = online.replace("ONLINE", "OFFLINE").replace("15:00:00", "14:59:00");
        router.route("iot/" + dos + "/status", bytes(older));
        Device device = devices.findBySerial(dos).orElseThrow();
        assertThat(device.getConnectivity()).isEqualTo(ConnectivityStatus.ONLINE);
        assertThat(device.getConnectivityEdgeNodeId()).isEqualTo(plantEdge);
        assertThat(auditTypes()).containsOnlyOnce(AuditEntryType.CONNECTIVITY_CHANGED);
    }

    @Test
    void anInactiveDeviceGoesOffline() {
        String online = industrial("device-status").replace("EMSafe-DOS-01", dos).replace("EDGE-PLANTA-01", plantEdge)
                .replace("2026-11-12T15:00:00Z", "2020-01-01T00:00:00Z");
        router.route("iot/" + dos + "/status", bytes(online));
        assertThat(connectivity.detectInactiveDevices()).isGreaterThanOrEqualTo(1);
        assertThat(devices.findBySerial(dos).orElseThrow().getConnectivity()).isEqualTo(ConnectivityStatus.OFFLINE);
    }

    @Test
    void thePlantEdgeStatusAndItsLastWillReachPlantConfiguration() {
        router.route("edge/" + plantEdge + "/status", bytes(industrial("edge-online")));
        verify(plantEdges).recordConnectivity(plantEdge, true, Instant.parse("2026-11-12T15:00:00Z"));
        Instant before = Instant.now();
        router.route("edge/" + plantEdge + "/status", bytes(industrial("edge-lwt")));
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(plantEdges).recordConnectivity(eq(plantEdge), eq(false), at.capture());
        assertThat(at.getValue()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(edges.findByEdgeNodeId(plantEdge).orElseThrow().getConnectivity())
                .isEqualTo(ConnectivityStatus.OFFLINE);
    }

    @Test
    void theHomeEdgeStatusStaysInDeviceManagement() {
        router.route("edge/" + homeEdge + "/status", bytes(home("edge-online")));
        assertThat(edges.findByEdgeNodeId(homeEdge).orElseThrow().getConnectivity())
                .isEqualTo(ConnectivityStatus.ONLINE);
        verify(plantEdges, never()).recordConnectivity(eq(homeEdge), anyBoolean(), any());
    }

    @Test
    void thePlugStateConfirmsTheUsersCommand() {
        String commandId = plugCommands.requestForClient(house.getId(),
                devices.findBySerial(plug).orElseThrow().getId(), "OFF").commandId();
        String state = home("plug-state").replace("EMSafe-PLUG-01", plug)
                .replace("3b8e1f7a-2c4d-4e9b-8a1f-6d5c4b3a2e10", commandId);
        router.route("iot/" + plug + "/state", bytes(state));

        assertThat(devices.findBySerial(plug).orElseThrow().getPlugPower()).isEqualTo(PlugPower.OFF);
        assertThat(commandRepository.findByCommandId(commandId).orElseThrow().getStatus())
                .isEqualTo(PlugCommandStatus.CONFIRMED);
        router.route("iot/" + plug + "/state", bytes(state));
        assertThat(auditTypes(plug)).containsOnlyOnce(AuditEntryType.PLUG_STATE);
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private void sendIndustrial(long sequence, String capturedAt, String quality, String origin) {
        String json = industrial("telemetry").replace("EMSafe-DOS-01", dos).replace("org-demo", org)
                .replace("EDGE-PLANTA-01", plantEdge)
                .replace("\"sequence\": 1234", "\"sequence\": " + sequence)
                .replace("\"capturedAt\": \"2026-11-12T15:04:04Z\"", "\"capturedAt\": \"" + capturedAt + "\"")
                .replace("\"quality\": \"VALID\"", "\"quality\": \"" + quality + "\"")
                .replace("\"origin\": \"REALTIME\"", "\"origin\": \"" + origin + "\"");
        router.route("iot/" + dos + "/telemetry", bytes(json));
    }

    private TelemetryReading only() {
        List<TelemetryReading> rows = readings.findByDevice(dos, null, 10);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private DosimetryReading sentToDosimetry() {
        ArgumentCaptor<DosimetryReading> captor = ArgumentCaptor.forClass(DosimetryReading.class);
        verify(dosimetry, atLeastOnce()).ingest(eq("session-1"), captor.capture());
        return captor.getValue();
    }

    private List<AuditEntryType> auditTypes() {
        return auditTypes(dos);
    }

    private List<AuditEntryType> auditTypes(String serial) {
        Long id = devices.findBySerial(serial).orElseThrow().getId();
        return audit.findByDeviceIdOrderByOccurredAtDescIdDesc(id, Pageable.unpaged()).stream()
                .map(DeviceAuditEntry::getEntryType).toList();
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
