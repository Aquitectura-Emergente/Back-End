package com.emsafe.occupationalhealth;

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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

        workerId = userId(WORKER_EMAIL, Role.WORKER, ORG);
        userId(SSO_EMAIL, Role.SSO, ORG);
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

    private Long zoneWithReading(String name, double microtesla, String level) {
        Device device = devices.save(Device.builder()
                .name(name).type("Sensor").location(name).build());
        readings.save(RadiationReading.builder()
                .readingDate(LocalDate.now())
                .recordedAt(LocalDateTime.now())
                .value(microtesla)
                .level(level)
                .sensorId("IT-" + device.getId())
                .device(device)
                .build());
        return device.getId();
    }

    // ── Turnos ───────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
    @DisplayName("un rango de fechas invertido da 422")
    void invertedPeriodIsUnprocessable() throws Exception {
        mvc.perform(get(API + "/workers/" + workerId + "/shifts")
                        .param("from", "2026-12-01").param("to", "2026-11-01"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── Reubicación ──────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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

    @Test
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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
    @WithMockUser(username = SSO_EMAIL, roles = {"SSO"})
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
        return user(SSO_EMAIL).roles("SSO");
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
