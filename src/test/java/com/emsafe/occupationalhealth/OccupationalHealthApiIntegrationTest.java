package com.emsafe.occupationalhealth;

import com.emsafe.aiforecasting.application.ForecastingEventFactory;
import com.emsafe.aiforecasting.application.UpsertExposureSnapshotHandler;
import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.ports.ForecastingOutboxPort;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.aiforecasting.domain.ports.WorkerSnapshotRepository;
import com.emsafe.aiforecasting.infrastructure.ForecastingOutboxRelay;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.domain.model.UserStatus;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.iottelemetry.domain.model.Device;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.occupationalhealth.domain.model.MitigationAction;
import com.emsafe.occupationalhealth.domain.model.MitigationAuditEntry;
import com.emsafe.occupationalhealth.domain.model.MitigationOrigin;
import com.emsafe.occupationalhealth.domain.model.MitigationState;
import com.emsafe.occupationalhealth.domain.model.MitigationType;
import com.emsafe.occupationalhealth.infrastructure.persistence.ClosedSessionRecordRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.ComplianceReportRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.IdempotencyRecordRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationAuditEntryRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.ProcessedMessageRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import com.emsafe.personaldosimetry.domain.model.DosimetryEvent;
import com.emsafe.personaldosimetry.infrastructure.DosimetryOutboxRelay;
import com.emsafe.personaldosimetry.infrastructure.persistence.DosimetryEventRepository;
import com.emsafe.plantconfiguration.domain.model.PlantZone;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantZoneRepository;
import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Lo que los tests de dominio no pueden cubrir: controllers, handlers, política
 * de acceso, adaptadores y los códigos HTTP del informe §5.6.2.
 *
 * Estos casos estaban verificados solo a mano contra MySQL. Aquí quedan fijados.
 *
 * Se autentica con @WithMockUser en vez de pedir un JWT: SecurityConfig filtra
 * por rol y MitigationAccessPolicy resuelve el usuario por el email del
 * principal, así que el mock ejercita exactamente la misma ruta de
 * autorización sin arrastrar la emisión de tokens al test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ohtestdb;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000",
        "spring.flyway.enabled=false",
        // El job de retención no debe correr durante los tests.
        "emsafe.occupational-health.retention-enabled=false",
        "emsafe.occupational-health.report-storage-path=./target/test-compliance-reports"
})
class OccupationalHealthApiIntegrationTest {

    private static final String SSO_EMAIL = "sso-it@emsafe.test";
    private static final String WORKER_EMAIL = "worker-it@emsafe.test";
    private static final String ORPHAN_ADMIN_EMAIL = "orphan-admin-it@emsafe.test";
    private static final Long ORG = 1L;
    private static final String API = "/api/v1/occupational-health";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired DeviceRepository devices;
    @Autowired RadiationReadingRepository readings;
    @Autowired MitigationAuditEntryRepository auditLog;
    @Autowired MitigationActionRepository actions;
    @Autowired WorkerShiftRepository shifts;
    @Autowired IdempotencyRecordRepository idempotencyRecords;
    @Autowired ProcessedMessageRepository processedMessages;
    @Autowired ClosedSessionRecordRepository closedSessions;
    @Autowired ComplianceReportRepository reports;
    @Autowired PlantZoneRepository plantZones;
    @Autowired UpsertExposureSnapshotHandler forecastingSnapshots;
    @Autowired WorkerSnapshotRepository forecastingSnapshotStore;
    @Autowired ForecastingOutboxPort forecastingOutbox;
    @Autowired org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Autowired DosimetryEventRepository dosimetryEvents;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private Long workerId;
    private Long safeZoneId;
    private Long dangerZoneId;

    /**
     * Se crean zonas propias en vez de reutilizar las del seeder: los ids del
     * seeder dependen del orden de inserción, y una lectura con fecha propia es
     * lo único que hace determinista la clasificación de la zona.
     */
    @BeforeEach
    void setUp() {
        // La base H2 es la misma para toda la clase y estos flujos no se pueden
        // envolver en una transacción de test: la auditoría de denegaciones se
        // escribe con REQUIRES_NEW justamente para sobrevivir a un rollback, así
        // que un @Transactional en el test falsearía lo que se quiere probar.
        // Se limpia a mano, y en orden de dependencia.
        reports.deleteAll();
        actions.deleteAll();
        shifts.deleteAll();
        closedSessions.deleteAll();
        auditLog.deleteAll();
        idempotencyRecords.deleteAll();
        processedMessages.deleteAll();
        // Desde la V22 una zona es una entidad propia con sus sensores dentro, así
        // que la topología también se rehace en cada test: si se acumulara,
        // zoneStatistics vería las zonas de los métodos anteriores.
        readings.deleteAll();
        devices.deleteAll();
        plantZones.deleteAll();
        dosimetryEvents.deleteAll();

        workerId = userId(WORKER_EMAIL, Role.WORKER, ORG);
        userId(SSO_EMAIL, Role.SSO_RESPONSIBLE, ORG);
        userId(ORPHAN_ADMIN_EMAIL, Role.ADMIN, null);

        safeZoneId = zoneWithReading("IT zona segura", 10.0, "safe");
        dangerZoneId = zoneWithReading("IT zona peligrosa", 500.0, "danger");
    }

    private Long userId(String email, Role role, Long organizationId) {
        return users.findByEmail(email)
                .orElseGet(() -> users.save(AppUser.builder()
                        .email(email)
                        .name("IT " + role)
                        .initials("IT")
                        .passwordHash("{noop}irrelevant")
                        .role(role)
                        .organizationId(organizationId)
                        .status(UserStatus.ACTIVE)
                        .joinDate(LocalDate.now())
                        .build()))
                .getId();
    }

    /**
     * Crea una zona de verdad con un sensor dentro y una lectura fresca.
     *
     * <p>Antes de la V22 esto devolvía el id del dispositivo, porque una zona se
     * identificaba con el sensor que la cubría. Ahora la zona es una entidad de
     * `plantconfiguration` y el sensor apunta a ella, así que lo que se devuelve
     * es el id de la <b>zona</b>. El nivel de la zona lo sigue decidiendo la
     * lectura, que es lo único que hace determinista la clasificación.
     */
    private Long zoneWithReading(String name, double microtesla, String level) {
        PlantZone zone = plantZones.save(PlantZone.create(ORG, name, null));
        Device device = devices.save(Device.builder()
                .name(name).type("Sensor").location(name)
                .plantZoneId(zone.getId()).build());
        readings.save(RadiationReading.builder()
                .readingDate(LocalDate.now())
                .recordedAt(LocalDateTime.now())
                .value(microtesla)
                .level(level)
                .sensorId("IT-" + device.getId())
                .device(device)
                .build());
        return zone.getId();
    }

    // ── Turnos ───────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("el SSO abre un turno y lo consulta")
    void ssoStartsAndReadsAShift() throws Exception {
        startShift();

        mvc.perform(get(API + "/workers/" + workerId + "/shifts/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workerId").value(workerId))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.currentPlantZoneId").value(safeZoneId))
                .andExpect(jsonPath("$.data.assignments.length()").value(1));
    }

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("un segundo turno para el mismo operario da 409")
    void secondShiftConflicts() throws Exception {
        startShift();

        mvc.perform(post(API + "/workers/" + workerId + "/shifts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPositionId\":1001,\"plantZoneId\":" + safeZoneId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already has an active shift")));
    }

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("un rango de fechas invertido da 422")
    void invertedPeriodIsUnprocessable() throws Exception {
        mvc.perform(get(API + "/workers/" + workerId + "/shifts")
                        .param("from", "2026-12-01").param("to", "2026-11-01"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Reubicación ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("reubicar cierra el tramo vigente y abre el siguiente")
    void relocationReassignsTheShift() throws Exception {
        startShift();

        mvc.perform(post(API + "/mitigation-actions/relocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":" + workerId + ",\"toPositionId\":1001,"
                                + "\"toZoneId\":" + safeZoneId + ",\"justification\":\"IT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("RELOCATION"))
                .andExpect(jsonPath("$.data.state").value("APPLIED"))
                .andExpect(jsonPath("$.data.origin").value("MANUAL"))
                // PROPOSED → APPROVED → APPLIED, con la decisión registrada
                .andExpect(jsonPath("$.data.history.length()").value(3))
                .andExpect(jsonPath("$.data.decision.decidedBy").exists());

        mvc.perform(get(API + "/workers/" + workerId + "/shifts/current"))
                .andExpect(jsonPath("$.data.assignments.length()").value(2))
                .andExpect(jsonPath("$.data.assignments[0].to").exists())
                .andExpect(jsonPath("$.data.assignments[1].sourceActionId").exists());
    }

    /**
     * El tramo que faltaba: que el {@code ShiftUpdated} llegue de verdad al inbox
     * de AI Forecasting.
     *
     * <p>No basta el test del listener: esto es lo único que comprueba que Spring
     * lo invoca tras el commit y que la transacción nueva persiste. Con la
     * propagación por defecto el guardado se perdería sin error visible.</p>
     */
    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("reubicar mueve también el snapshot de AI Forecasting")
    void relocationReachesTheForecastingInbox() throws Exception {
        String sessionId = "S-IT-E2E";
        Long toZoneId = zoneWithReading("IT zona segura 2", 12.0, "safe");
        startShiftLinkedTo(sessionId);
        seedForecastingSnapshot(workerId, sessionId, String.valueOf(safeZoneId), 1001L);

        mvc.perform(post(API + "/mitigation-actions/relocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":" + workerId + ",\"toPositionId\":1002,"
                                + "\"toZoneId\":" + toZoneId + ",\"justification\":\"IT\"}"))
                .andExpect(status().isCreated());

        WorkerExposureSnapshot moved = forecastingSnapshotStore.findBySessionId(sessionId).orElseThrow();
        // La zona viaja como el id decimal de plant_zones: es la identidad canónica.
        assertEquals(String.valueOf(toZoneId), moved.zoneId());
        assertEquals(1002L, moved.positionId());
    }

    /**
     * Un operario puede rotar sin que AI Forecasting siga su sesión. El inbox
     * responde 409 y eso no debe volverse un error del SSO: la reubicación ya
     * ocurrió y no se puede deshacer.
     */
    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("reubicar funciona aunque AI Forecasting no conozca la sesión")
    void relocationSucceedsWhenForecastingHasNoSnapshot() throws Exception {
        Long toZoneId = zoneWithReading("IT zona segura 3", 11.0, "safe");
        startShiftLinkedTo("S-IT-UNKNOWN");

        mvc.perform(post(API + "/mitigation-actions/relocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":" + workerId + ",\"toPositionId\":1002,"
                                + "\"toZoneId\":" + toZoneId + ",\"justification\":\"IT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.state").value("APPLIED"));

        mvc.perform(get(API + "/workers/" + workerId + "/shifts/current"))
                .andExpect(jsonPath("$.data.assignments.length()").value(2));
        assertTrue(forecastingSnapshotStore.findBySessionId("S-IT-UNKNOWN").isEmpty());
    }

    /**
     * El círculo completo con el contexto del compañero: AI Forecasting sugiere
     * la rotación, el SSO la aprueba y el resultado vuelve a AI Forecasting.
     *
     * <p>Una rotación es un intercambio, así que aprobarla publica <b>dos</b>
     * {@code ShiftUpdated} —uno por operario— y los dos snapshots tienen que
     * acabar cruzados.</p>
     */
    @Test
    @DisplayName("aprobar una rotación sugerida cruza los dos snapshots de AI Forecasting")
    void approvedRotationReachesTheForecastingInbox() throws Exception {
        Long partnerId = userId("partner-it@emsafe.test", Role.WORKER, ORG);
        Long partnerZoneId = zoneWithReading("IT zona segura 4", 15.0, "safe");

        startShiftFor(workerId, 1001L, safeZoneId, "S-IT-AT-RISK");
        startShiftFor(partnerId, 1002L, partnerZoneId, "S-IT-PARTNER");
        seedForecastingSnapshot(workerId, "S-IT-AT-RISK", String.valueOf(safeZoneId), 1001L);
        seedForecastingSnapshot(partnerId, "S-IT-PARTNER", String.valueOf(partnerZoneId), 1002L);

        mvc.perform(post(API + "/inbox/rotation-suggested")
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":" + ORG + ",\"workerId\":" + workerId
                                + ",\"suggestionId\":\"sg-e2e\",\"candidateWorkerIds\":["
                                + partnerId + "],\"rationale\":\"proyeccion IT\"}"))
                .andExpect(status().isAccepted());

        List<MitigationAction> open = actions.findOpenByWorker(ORG, workerId);
        assertEquals(1, open.size(), "la sugerencia viable deja una rotación propuesta");
        MitigationAction rotation = open.get(0);
        assertEquals(MitigationType.ROTATION, rotation.getType());
        assertEquals(MitigationOrigin.AI_SUGGESTION, rotation.getOrigin());

        mvc.perform(post(API + "/mitigation-actions/" + rotation.getId() + "/approve")
                        .with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"aprobada IT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("APPLIED"));

        // Cada uno ocupa ahora el puesto y la zona del otro.
        WorkerExposureSnapshot atRisk = forecastingSnapshotStore
                .findBySessionId("S-IT-AT-RISK").orElseThrow();
        assertEquals(String.valueOf(partnerZoneId), atRisk.zoneId());
        assertEquals(1002L, atRisk.positionId());

        WorkerExposureSnapshot partner = forecastingSnapshotStore
                .findBySessionId("S-IT-PARTNER").orElseThrow();
        assertEquals(String.valueOf(safeZoneId), partner.zoneId());
        assertEquals(1001L, partner.positionId());
    }

    // ── El relay del outbox de AI Forecasting ────────────────────────────────

    /**
     * El sentido contrario: lo que el agente sugiere tiene que llegar aquí.
     *
     * <p>Antes del relay, {@code RotationSuggested} se quedaba en {@code af_outbox}
     * y la única forma de que entrara era un POST manual al inbox.</p>
     */
    @Test
    @DisplayName("el relay entrega una sugerencia del outbox y la deja propuesta")
    void relayDeliversRotationSuggested() throws Exception {
        Long partnerId = userId("partner-relay@emsafe.test", Role.WORKER, ORG);
        Long partnerZoneId = zoneWithReading("IT zona relay", 14.0, "safe");
        startShiftFor(workerId, 1001L, safeZoneId, "S-RELAY-A");
        startShiftFor(partnerId, 1002L, partnerZoneId, "S-RELAY-B");
        forecastingOutbox.append(rotationSuggestedInOutbox("af-ev-1", partnerId));

        assertEquals(1, relay().drainOnce());

        List<MitigationAction> open = actions.findOpenByWorker(ORG, workerId);
        assertEquals(1, open.size(), "la sugerencia entra como una propuesta");
        assertEquals(MitigationType.ROTATION, open.get(0).getType());
        assertEquals(MitigationOrigin.AI_SUGGESTION, open.get(0).getOrigin());
        assertNotNull(forecastingOutbox.findById("af-ev-1").orElseThrow().publishedAt(),
                "entregado es publicado");
    }

    @Test
    @DisplayName("una segunda pasada no reentrega ni duplica la propuesta")
    void relayDoesNotRedeliver() throws Exception {
        Long partnerId = userId("partner-relay2@emsafe.test", Role.WORKER, ORG);
        Long partnerZoneId = zoneWithReading("IT zona relay 2", 13.0, "safe");
        startShiftFor(workerId, 1001L, safeZoneId, "S-RELAY-C");
        startShiftFor(partnerId, 1002L, partnerZoneId, "S-RELAY-D");
        forecastingOutbox.append(rotationSuggestedInOutbox("af-ev-2", partnerId));

        relay().drainOnce();
        assertEquals(0, relay().drainOnce(), "ya no está pendiente");

        assertEquals(1, actions.findOpenByWorker(ORG, workerId).size());
    }

    /**
     * Si no se marcaran, encabezarían la cola para siempre y taparían los hechos
     * siguientes. Marcar no borra: el evento sigue en la tabla.
     */
    @Test
    @DisplayName("un hecho sin consumidor se marca y no atasca la cola")
    void relayMarksEventsWithoutConsumer() {
        forecastingOutbox.append(new OutboxEvent("af-ev-3",
                ForecastingEventFactory.PREDICTIVE_RISK_DETECTED, OutboxEvent.SCHEMA_VERSION,
                ORG, "assessment-1", Instant.now().truncatedTo(ChronoUnit.MICROS),
                "corr-3", null, Map.of("workerId", workerId, "criticality", "HIGH"), null));

        assertEquals(1, relay().drainOnce());

        assertNotNull(forecastingOutbox.findById("af-ev-3").orElseThrow().publishedAt());
        assertTrue(actions.findOpenByWorker(ORG, workerId).isEmpty(),
                "no abre ninguna medida: nadie lo consume todavía");
    }

    /**
     * Se construye a mano porque el bean está apagado en el perfil de test: un
     * @Scheduled dispararía en medio de los asserts. Lo que no queda cubierto es
     * el cableado del scheduler, igual que pasa con RetentionJob.
     */
    private ForecastingOutboxRelay relay() {
        return new ForecastingOutboxRelay(forecastingOutbox, eventPublisher, Clock.systemUTC());
    }

    private Map<String, Object> rotationPayload(Long candidateId) {
        return Map.of(
                "organizationId", ORG,
                "workerId", workerId,
                "suggestionId", "assessment-sg",
                "candidateWorkerIds", List.of(candidateId),
                "projectedExceedanceAt", LocalDateTime.now().plusHours(2)
                        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                "criticality", "HIGH",
                "rationale", "proyeccion del agente");
    }

    private OutboxEvent rotationSuggestedInOutbox(String eventId, Long candidateId) {
        return new OutboxEvent(eventId, ForecastingEventFactory.ROTATION_SUGGESTED,
                OutboxEvent.SCHEMA_VERSION, ORG, "assessment-sg",
                Instant.now().truncatedTo(ChronoUnit.MICROS), "corr-" + eventId, "cause-" + eventId,
                rotationPayload(candidateId), null);
    }

    // ── El relay del outbox de Personal Dosimetry ────────────────────────────

    /**
     * Una excedencia confirmada por dosimetría tiene que abrir una medida sola.
     *
     * <p>Lo que se vigila aquí es la <b>unidad</b>: lo que viaja es un índice
     * {@code uT*s}, no una dosis certificada, y el snapshot tiene que conservarlo
     * tal cual. Si se perdiera, un reporte de cumplimiento afirmaría una cifra sin
     * decir en qué está medida.</p>
     */
    @Test
    @DisplayName("el relay entrega la excedencia y conserva la unidad del indice")
    void relayDeliversExceedanceKeepingTheUnit() throws Exception {
        startShiftLinkedTo("sess-dose");
        dosimetryEvents.save(dosimetryOutboxEvent(
                DosimetryOutboxRelay.LIMIT_EXCEEDED, exceedancePayload()));

        assertEquals(1, dosimetryRelay().drainOnce());

        List<MitigationAction> open = actions.findOpenByWorker(ORG, workerId);
        assertEquals(1, open.size(), "la excedencia abre una propuesta");
        MitigationAction proposal = open.get(0);
        assertEquals(MitigationState.PROPOSED, proposal.getState());
        assertEquals(MitigationOrigin.LIMIT_EXCEEDED, proposal.getOrigin());
        assertTrue(proposal.hasPendingTarget(), "la excedencia no dice a donde llevarlo");

        var dose = proposal.getAffectedWorkers().get(0).getDoseAtDecision();
        assertEquals(0, dose.getValue().compareTo(new BigDecimal("900")));
        assertEquals("uT*s", dose.getUnit(), "la unidad del indice no se pierde");
        assertEquals(0, dose.getLimit().compareTo(new BigDecimal("800")));
        // Antes del 2026-11-12 aqui se guardaba el limitVersionId ("L1").
        assertEquals("MAGNETIC_TIME_INDEX_V1", dose.getMethodId(),
                "dose_method_id guarda el metodo de calculo, no la version del limite");

        assertNotNull(dosimetryEvents.findAll().get(0).getPublishedAt());
    }

    /**
     * El cierre de jornada alimenta la proyección que exportan los reportes de
     * cumplimiento, así que se comprueba que llega entera: cifra, unidad, método
     * y cobertura.
     */
    @Test
    @DisplayName("el cierre de jornada llega a la proyeccion con sus cuatro datos")
    void relayDeliversSessionClosed() throws Exception {
        startShiftLinkedTo("sess-dose");
        dosimetryEvents.save(dosimetryOutboxEvent(
                DosimetryOutboxRelay.SESSION_CLOSED, sessionClosedPayload(true)));

        assertEquals(1, dosimetryRelay().drainOnce());

        var record = closedSessions.findByOrganizationIdAndSessionId(ORG, "sess-dose").orElseThrow();
        assertEquals(0, record.getFinalDose().getValue().compareTo(new BigDecimal("600")));
        assertEquals("uT*s", record.getFinalDose().getUnit());
        assertEquals("MAGNETIC_TIME_INDEX_V1", record.getFinalDose().getMethodId(),
                "el metodo de calculo real, no el limitVersionId");
        assertEquals(0, record.getFinalDose().getLimit().compareTo(new BigDecimal("800")));
        assertEquals("98.50% (completa)", record.getCoverage(),
                "porcentaje y completitud en el unico campo de texto del destino");
        assertNotNull(record.getFinalizedAt());

        // El turno vinculado a esa jornada queda cerrado.
        assertTrue(shifts.findActiveByWorker(ORG, workerId).isEmpty());
    }

    /**
     * Los eventos emitidos antes de que el payload llevara {@code finalizedAt}
     * siguen pendientes en la tabla. El destino lo exige no nulo, así que sin
     * respaldo se reintentarían y fallarían en cada pasada para siempre.
     */
    @Test
    @DisplayName("un cierre de jornada antiguo sin finalizedAt se entrega igual")
    void relayFallsBackToOccurredAtForLegacyEvents() throws Exception {
        startShiftLinkedTo("sess-dose");
        var legacy = new java.util.LinkedHashMap<>(sessionClosedPayload(false));
        legacy.remove("finalizedAt");
        dosimetryEvents.save(dosimetryOutboxEvent(DosimetryOutboxRelay.SESSION_CLOSED, legacy));

        assertEquals(1, dosimetryRelay().drainOnce(), "no se queda atascado");

        var record = closedSessions.findByOrganizationIdAndSessionId(ORG, "sess-dose").orElseThrow();
        assertNotNull(record.getFinalizedAt(), "se usa el occurredAt del evento");
        assertEquals("98.50% (incompleta)", record.getCoverage());
    }

    /**
     * Los tipos sin destino no se marcan: marcarlos perdería el hecho. Quedan dos
     * —{@code PersonalReadingArrivedLate} y {@code ExposureSessionCloseRequested}—;
     * {@code ExposureWarningRaised} ya se entrega a AI Forecasting.
     */
    @Test
    @DisplayName("un hecho sin destino se queda pendiente y no atasca a los demas")
    void relayLeavesEventsWithoutDestinationPending() throws Exception {
        startShiftLinkedTo("sess-dose");
        dosimetryEvents.save(dosimetryOutboxEvent("PersonalReadingArrivedLate",
                Map.of("sessionId", "sess-dose", "readingId", 1L)));
        dosimetryEvents.save(dosimetryOutboxEvent(
                DosimetryOutboxRelay.LIMIT_EXCEEDED, exceedancePayload()));

        // El no entregable se insertó primero: si la consulta no filtrara por
        // tipo, encabezaría la cola y taparía la excedencia.
        assertEquals(1, dosimetryRelay().drainOnce());

        var late = dosimetryEvents.findAll().stream()
                .filter(e -> e.getEventType().equals("PersonalReadingArrivedLate"))
                .findFirst().orElseThrow();
        assertNull(late.getPublishedAt(), "sigue pendiente para cuando tenga consumidor");
        assertEquals(1, actions.findOpenByWorker(ORG, workerId).size());
    }

    private Map<String, Object> sessionClosedPayload(boolean coverageComplete) {
        return Map.ofEntries(
                Map.entry("sessionId", "sess-dose"),
                Map.entry("workerId", workerId),
                Map.entry("accumulatedIndex", new BigDecimal("600")),
                Map.entry("limitIndex", new BigDecimal("800")),
                Map.entry("limitVersionId", "L1"),
                Map.entry("coveragePercentage", new BigDecimal("98.50")),
                Map.entry("coverageComplete", coverageComplete),
                Map.entry("finalizedBy", 1L),
                Map.entry("finalizedAt", Instant.now().truncatedTo(ChronoUnit.MICROS)),
                Map.entry("methodId", "MAGNETIC_TIME_INDEX_V1"),
                Map.entry("unit", "uT*s"));
    }

    /** Igual que el de forecasting: el bean está apagado en el perfil de test. */
    private DosimetryOutboxRelay dosimetryRelay() {
        return new DosimetryOutboxRelay(dosimetryEvents, eventPublisher, objectMapper, Clock.systemUTC());
    }

    /** Los mismos campos y el mismo ObjectMapper que usa PersonalDosimetryService.emit. */
    private DosimetryEvent dosimetryOutboxEvent(String type, Map<String, Object> fields) {
        var payload = new java.util.LinkedHashMap<String, Object>(fields);
        payload.put("schemaVersion", 1);
        payload.put("organizationId", ORG);
        try {
            return new DosimetryEvent(ORG, type, objectMapper.writeValueAsString(payload),
                    Instant.now().truncatedTo(ChronoUnit.MICROS));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> exceedancePayload() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return Map.of(
                "sessionId", "sess-dose",
                "workerId", workerId,
                "readingId", 1L,
                "accumulatedIndex", new BigDecimal("900"),
                "limitIndex", new BigDecimal("800"),
                "limitVersionId", "L1",
                "capturedAt", now.minusSeconds(60),
                "detectedAt", now,
                "methodId", "MAGNETIC_TIME_INDEX_V1",
                "unit", "uT*s");
    }

    private void startShiftLinkedTo(String exposureSessionId) throws Exception {
        startShiftFor(workerId, 1001L, safeZoneId, exposureSessionId);
    }

    private void startShiftFor(Long worker, Long positionId, Long zoneId, String exposureSessionId)
            throws Exception {
        mvc.perform(post(API + "/workers/" + worker + "/shifts")
                        .with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPositionId\":" + positionId + ",\"plantZoneId\":" + zoneId
                                + ",\"exposureSessionId\":\"" + exposureSessionId + "\"}"))
                .andExpect(status().isCreated());
    }

    /** Deja a AI Forecasting siguiendo la sesión, como si hubiera llegado por su inbox. */
    private void seedForecastingSnapshot(Long worker, String sessionId, String zoneId,
                                         Long positionId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        forecastingSnapshots.handle(new UpsertExposureSnapshotCommand(
                "snap-" + sessionId, null, ORG, worker, sessionId, zoneId, positionId,
                new BigDecimal("1000"), new BigDecimal("12000"),
                now.minus(4, ChronoUnit.HOURS), now.plus(4, ChronoUnit.HOURS), now));
    }

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("reubicar a una zona de riesgo da 422 y no toca el turno")
    void relocationToRiskZoneIsRejected() throws Exception {
        startShift();

        mvc.perform(post(API + "/mitigation-actions/relocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":" + workerId + ",\"toPositionId\":1001,"
                                + "\"toZoneId\":" + dangerZoneId + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("RISK")));

        mvc.perform(get(API + "/workers/" + workerId + "/shifts/current"))
                .andExpect(jsonPath("$.data.assignments.length()").value(1));
    }

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("la misma Idempotency-Key no reubica dos veces")
    void idempotencyKeyPreventsDoubleRelocation() throws Exception {
        startShift();
        String body = "{\"workerId\":" + workerId + ",\"toPositionId\":1001,"
                + "\"toZoneId\":" + safeZoneId + "}";

        MvcResult first = mvc.perform(post(API + "/mitigation-actions/relocations")
                        .header("Idempotency-Key", "it-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mvc.perform(post(API + "/mitigation-actions/relocations")
                        .header("Idempotency-Key", "it-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();

        assertEquals(idOf(first), idOf(second),
                "la repetición devuelve la misma acción, no una nueva");
        mvc.perform(get(API + "/workers/" + workerId + "/shifts/current"))
                .andExpect(jsonPath("$.data.assignments.length()").value(2));
    }

    // ── Control de acceso ────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = WORKER_EMAIL, roles = {"WORKER"})
    @DisplayName("un operario no puede decidir medidas: 403 y queda auditado")
    void workerCannotDecide() throws Exception {
        long deniedBefore = auditLog
                .findByOutcomeAndOccurredAtBefore(MitigationAuditEntry.DENIED,
                        LocalDateTime.now().plusMinutes(1)).size();

        mvc.perform(post(API + "/mitigation-actions/relocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":" + workerId + ",\"toPositionId\":1,"
                                + "\"toZoneId\":" + safeZoneId + "}"))
                .andExpect(status().isForbidden());

        long deniedAfter = auditLog
                .findByOutcomeAndOccurredAtBefore(MitigationAuditEntry.DENIED,
                        LocalDateTime.now().plusMinutes(1)).size();
        assertEquals(deniedBefore + 1, deniedAfter,
                "la denegación sobrevive al rollback: se escribe en su propia transacción");
    }

    @Test
    @WithMockUser(username = WORKER_EMAIL, roles = {"WORKER"})
    @DisplayName("un operario consulta su turno pero no el de otro")
    void workerSeesOnlyItsOwnShift() throws Exception {
        mvc.perform(get(API + "/workers/" + workerId + "/shifts/current"))
                .andExpect(status().isNotFound());   // sin turno abierto, pero autorizado

        mvc.perform(get(API + "/workers/" + (workerId + 999) + "/shifts/current"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ORPHAN_ADMIN_EMAIL, roles = {"ADMIN"})
    @DisplayName("un ADMIN sin organización no ve nada industrial")
    void adminWithoutOrganizationIsDenied() throws Exception {
        mvc.perform(get(API + "/mitigation-actions"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sin autenticar no se entra")
    void anonymousIsRejected() throws Exception {
        mvc.perform(get(API + "/mitigation-actions"))
                .andExpect(status().is4xxClientError());
    }

    // ── Entrada de eventos de otros contextos ────────────────────────────────

    @Test
    @WithMockUser(username = "admin-it@emsafe.test", roles = {"ADMIN"})
    @DisplayName("una excedencia confirmada abre una propuesta sin destino")
    void limitExceededOpensAProposal() throws Exception {
        mvc.perform(post(API + "/inbox/occupational-limit-exceeded")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":" + ORG + ",\"workerId\":" + workerId
                                + ",\"sessionId\":\"s-it-1\",\"dose\":900,\"unit\":\"uT.h\","
                                + "\"limit\":800,\"limitVersionId\":\"L1\"}"))
                .andExpect(status().isAccepted());

        List<MitigationAction> open = actions.findOpenByWorker(ORG, workerId);
        assertEquals(1, open.size(), "la excedencia abre una sola propuesta");
        MitigationAction proposal = open.get(0);
        assertEquals(MitigationState.PROPOSED, proposal.getState());
        assertEquals(MitigationOrigin.LIMIT_EXCEEDED, proposal.getOrigin());
        assertTrue(proposal.hasPendingTarget(),
                "el hecho dice de dónde sacar al operario, no a dónde llevarlo");
        assertNotNull(proposal.getTrigger().getSourceEventId(),
                "queda la referencia al hecho que la originó");
    }

    @Test
    @WithMockUser(username = "admin-it@emsafe.test", roles = {"ADMIN"})
    @DisplayName("el mismo hecho dos veces no abre dos propuestas")
    void duplicateEventIsIgnored() throws Exception {
        String body = "{\"organizationId\":" + ORG + ",\"workerId\":" + workerId
                + ",\"sessionId\":\"s-it-2\",\"dose\":900,\"unit\":\"uT.h\",\"limit\":800}";

        mvc.perform(post(API + "/inbox/occupational-limit-exceeded")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mvc.perform(post(API + "/inbox/occupational-limit-exceeded")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        // Cada POST genera su propio eventId, así que el inbox no los ve
        // iguales: lo que impide dos medidas vivas es la regla 5, que desplaza
        // la anterior y deja una sola propuesta por operario.
        assertEquals(1, actions.findOpenByWorker(ORG, workerId).size(),
                "solo puede quedar una medida viva por operario");

        long superseded = actions.findByWorkerAndPeriod(ORG, workerId,
                        LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1))
                .stream()
                .filter(a -> a.getSupersededById() != null)
                .count();
        assertEquals(1, superseded, "la propuesta desplazada apunta a la que la reemplazó");
    }

    @Test
    @DisplayName("una jornada cerrada alimenta la proyección y cierra el turno")
    void sessionClosedStoresTheProjectionAndClosesTheShift() throws Exception {
        startShiftAsSso();

        mvc.perform(post(API + "/inbox/exposure-session-closed")
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionClosedBody("sess-it", "600")))
                .andExpect(status().isAccepted());

        assertTrue(closedSessions.findByOrganizationIdAndSessionId(ORG, "sess-it").isPresent());
        assertTrue(shifts.findActiveByWorker(ORG, workerId).isEmpty(),
                "el turno vinculado a esa jornada queda cerrado");
    }

    @Test
    @DisplayName("una sugerencia sin candidatos viables propone reprogramar")
    void rotationWithoutViableCandidateProposesRescheduling() throws Exception {
        startShiftAsSso();

        mvc.perform(post(API + "/inbox/rotation-suggested")
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationId\":" + ORG + ",\"workerId\":" + workerId
                                + ",\"suggestionId\":\"sg-1\",\"candidateWorkerIds\":[],"
                                + "\"rationale\":\"proyeccion IT\"}"))
                .andExpect(status().isAccepted());

        // findOpenByWorker excluye RESCHEDULING a propósito: una reprogramación
        // no ocupa al operario, así que no compite por él (regla 5). Se consulta
        // por período.
        List<MitigationAction> all = actions.findByWorkerAndPeriod(ORG, workerId,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));

        assertEquals(1, all.stream()
                        .filter(a -> a.getType() == MitigationType.RESCHEDULING)
                        .filter(a -> a.getState() == MitigationState.PROPOSED)
                        .count(),
                "sin candidato con margen, US61 escenario 2 propone reprogramar");
        assertEquals(1, all.stream()
                        .filter(a -> a.getType() == MitigationType.ROTATION)
                        .filter(a -> a.getState() == MitigationState.NOT_VIABLE)
                        .count(),
                "y la sugerencia original queda cerrada como no viable");
    }

    // ── Reportes de cumplimiento ─────────────────────────────────────────────

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("el reporte por trabajador sale trazable y con su digest verificado")
    void workerReportIsTraceableAndVerified() throws Exception {
        seedClosedSession("sess-report-1", "600");

        mvc.perform(post(API + "/compliance-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"WORKER\",\"workerId\":" + workerId
                                + ",\"from\":\"" + LocalDate.now().minusDays(1)
                                + "\",\"to\":\"" + LocalDate.now().plusDays(1) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scope").value("WORKER"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.digestAlgorithm").value("SHA-256"))
                .andExpect(jsonPath("$.data.evidenceVerified").value(true))
                .andExpect(jsonPath("$.data.fullyTraceable").value(true))
                .andExpect(jsonPath("$.data.entriesWithoutSource").value(0))
                .andExpect(jsonPath("$.data.entries[0].sources[0].type").value("SESSION"));
    }

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("pedir el mismo reporte otra vez crea una versión nueva, no lo sobrescribe")
    void regeneratingCreatesANewVersion() throws Exception {
        seedClosedSession("sess-report-2", "600");
        String body = "{\"scope\":\"WORKER\",\"workerId\":" + workerId
                + ",\"from\":\"" + LocalDate.now().minusDays(1)
                + "\",\"to\":\"" + LocalDate.now().plusDays(1) + "\"}";

        mvc.perform(post(API + "/compliance-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(post(API + "/compliance-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("exportar devuelve un PDF y queda auditado")
    void exportReturnsAPdfAndIsAudited() throws Exception {
        seedClosedSession("sess-report-3", "600");
        MvcResult created = mvc.perform(post(API + "/compliance-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"WORKER\",\"workerId\":" + workerId
                                + ",\"from\":\"" + LocalDate.now().minusDays(1)
                                + "\",\"to\":\"" + LocalDate.now().plusDays(1) + "\"}"))
                .andExpect(status().isCreated()).andReturn();

        MvcResult exported = mvc.perform(get(API + "/compliance-reports/"
                        + idOf(created) + "/export").param("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        byte[] pdf = exported.getResponse().getContentAsByteArray();
        assertTrue(pdf.length > 500, "el PDF no puede venir vacío");
        assertEquals("%PDF", new String(pdf, 0, 4), "y tiene que ser un PDF de verdad");

        assertTrue(auditLog.findAll().stream().anyMatch(
                        e -> MitigationAuditEntry.ACTION_EXPORT_REPORT.equals(e.getAction())),
                "toda exportación de reporte queda registrada (Ley 29733)");
    }

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("un reporte por zona no lleva ningún identificador de operario")
    void zoneReportCarriesNoWorkerData() throws Exception {
        mvc.perform(post(API + "/compliance-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PLANT_ZONE\",\"from\":\""
                                + LocalDate.now().minusDays(1) + "\",\"to\":\""
                                + LocalDate.now().plusDays(1) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scope").value("PLANT_ZONE"))
                .andExpect(jsonPath("$.data.subjectWorkerId").doesNotExist())
                .andExpect(jsonPath("$.data.entries[0].sessionId").doesNotExist())
                .andExpect(jsonPath("$.data.entries[0].finalDose").doesNotExist())
                .andExpect(jsonPath("$.data.entries[0].zone.zoneId").exists());
    }

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO_RESPONSIBLE"})
    @DisplayName("un reporte por zona que señale a un trabajador da 422")
    void zoneReportWithWorkerIsUnprocessable() throws Exception {
        mvc.perform(post(API + "/compliance-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\":\"PLANT_ZONE\",\"workerId\":" + workerId
                                + ",\"from\":\"" + LocalDate.now().minusDays(1)
                                + "\",\"to\":\"" + LocalDate.now().plusDays(1) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("RF32")));
    }

    // ── Ayudas ───────────────────────────────────────────────────────────────

    /**
     * Siembra una jornada cerrada directamente por el repositorio.
     *
     * Pasar por el inbox exigiría autenticarse como ADMIN, y @WithMockUser fija
     * el usuario para todo el test: cambiarlo a mitad no es fiable. El consumidor
     * que la crea ya tiene su propio test.
     */
    private void seedClosedSession(String sessionId, String dose) {
        closedSessions.save(com.emsafe.occupationalhealth.domain.model.ClosedSessionRecord.builder()
                .organizationId(ORG)
                .sessionId(sessionId)
                .workerId(workerId)
                .finalDose(com.emsafe.occupationalhealth.domain.model.DoseSnapshot.of(
                        new java.math.BigDecimal(dose), "uT.h", "M1",
                        new java.math.BigDecimal("800")))
                .coverage("COMPLETE")
                .finalizedAt(LocalDateTime.now())
                .build());
    }

    private String sessionClosedBody(String sessionId, String dose) {
        return "{\"organizationId\":" + ORG + ",\"workerId\":" + workerId
                + ",\"sessionId\":\"" + sessionId + "\",\"finalDose\":" + dose
                + ",\"unit\":\"uT.h\",\"limit\":800,\"methodId\":\"M1\""
                + ",\"coverage\":\"COMPLETE\",\"finalizedAt\":\""
                + LocalDateTime.now().withNano(0) + "\"}";
    }

    /**
     * Abre el turno autenticando la petición como SSO.
     *
     * Se usa el post-processor por petición en vez de @WithMockUser porque estos
     * tests necesitan dos principales distintos —el turno es del SSO y el inbox
     * del ADMIN— y la anotación fija uno solo para todo el método. Manipular el
     * SecurityContextHolder a mano no sirve: MockMvc resuelve el contexto por
     * petición y la restauración no se refleja.
     */
    private void startShiftAsSso() throws Exception {
        mvc.perform(post(API + "/workers/" + workerId + "/shifts")
                        .with(asSso())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPositionId\":1000,\"plantZoneId\":" + safeZoneId
                                + ",\"exposureSessionId\":\"sess-it\"}"))
                .andExpect(status().isCreated());
    }

    private static RequestPostProcessor asSso() {
        return user(SSO_EMAIL).roles("SSO_RESPONSIBLE");
    }

    private static RequestPostProcessor asAdmin() {
        return user("admin-it@emsafe.test").roles("ADMIN");
    }


    private void startShift() throws Exception {
        mvc.perform(post(API + "/workers/" + workerId + "/shifts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPositionId\":1000,\"plantZoneId\":" + safeZoneId
                                + ",\"exposureSessionId\":\"sess-it\"}"))
                .andExpect(status().isCreated());
    }

    private String idOf(MvcResult result) throws Exception {
        String json = result.getResponse().getContentAsString();
        int idx = json.indexOf("\"id\":");
        return json.substring(idx + 5, json.indexOf(',', idx));
    }
}
