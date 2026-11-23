package com.emsafe.occupationalhealth;

import com.emsafe.alerts.domain.model.Alert;
import com.emsafe.alerts.infrastructure.persistence.AlertRepository;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.domain.model.UserStatus;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.occupationalhealth.infrastructure.AlertEscalationJob;
import com.emsafe.occupationalhealth.infrastructure.persistence.AlertEvidenceRecordRepository;
import com.emsafe.personaldosimetry.domain.model.DosimetryEvent;
import com.emsafe.personaldosimetry.infrastructure.DosimetryOutboxRelay;
import com.emsafe.personaldosimetry.infrastructure.persistence.DosimetryEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alerta automática de exposición y escalamiento al SSO (2026-11-22).
 *
 * <p>Recorre el camino entero: el outbox de Personal Dosimetry → relay → evento
 * en Occupational Health → alerta del operario → (sin acuse) escalamiento al SSO
 * → evidencia. Y los listados y permisos nuevos de {@code /api/alarms}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:exposure_alerts;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000",
        "spring.flyway.enabled=false",
        "emsafe.occupational-health.retention-enabled=false"
})
class ExposureAlertIntegrationTest {

    private static final Long ORG = 501L;
    private static final Long OTHER_ORG = 502L;
    private static final String WORKER_EMAIL = "alert-worker@emsafe.test";
    private static final String OTHER_WORKER_EMAIL = "alert-other-worker@emsafe.test";
    private static final String SSO_EMAIL = "alert-sso@emsafe.test";
    private static final String OTHER_SSO_EMAIL = "alert-other-sso@emsafe.test";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AlertRepository alerts;
    @Autowired AlertEvidenceRecordRepository evidence;
    @Autowired DosimetryEventRepository dosimetryEvents;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired ObjectMapper objectMapper;
    @Autowired AlertEscalationJob escalationJob;

    private Long workerId;
    private Long otherWorkerId;
    private Long ssoId;

    @BeforeEach
    void setUp() {
        dosimetryEvents.deleteAll();
        alerts.findAll().stream()
                .filter(a -> ORG.equals(a.getOrganizationId()) || OTHER_ORG.equals(a.getOrganizationId()))
                .forEach(alerts::delete);
        workerId = userId(WORKER_EMAIL, Role.WORKER, ORG);
        otherWorkerId = userId(OTHER_WORKER_EMAIL, Role.WORKER, OTHER_ORG);
        ssoId = userId(SSO_EMAIL, Role.SSO_RESPONSIBLE, ORG);
        userId(OTHER_SSO_EMAIL, Role.SSO_RESPONSIBLE, OTHER_ORG);
    }

    // ─── Creación automática ─────────────────────────────────────────────────

    @Test
    @DisplayName("una excedencia confirmada crea una alerta danger para el operario")
    void limitExceededRaisesADangerAlert() {
        dosimetryEvents.save(outboxEvent(DosimetryOutboxRelay.LIMIT_EXCEEDED));

        relay().drainOnce();

        List<Alert> mine = alerts.findByWorkerIdOrderByCreatedAtDesc(workerId);
        assertThat(mine).hasSize(1);
        Alert alert = mine.get(0);
        assertThat(alert.getType()).isEqualTo("danger");
        assertThat(alert.getOrganizationId()).isEqualTo(ORG);
        assertThat(alert.getSessionId()).isEqualTo("sess-alert");
        assertThat(alert.getDescription()).contains("900 uT*s").contains("800 uT*s");
        assertThat(alert.getAcknowledgedAt()).isNull();
    }

    @Test
    @DisplayName("el aviso preventivo llega a Occupational Health y crea una alerta warning")
    void warningRaisedReachesOccupationalHealth() {
        dosimetryEvents.save(outboxEvent(DosimetryOutboxRelay.WARNING_RAISED));

        // AI Forecasting puede responder 409 (sin snapshot) y dejar el evento
        // pendiente: el aviso al operario no debe depender de eso.
        relay().drainOnce();

        List<Alert> mine = alerts.findByWorkerIdOrderByCreatedAtDesc(workerId);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getType()).isEqualTo("warning");
    }

    @Test
    @DisplayName("reentregar el mismo hecho no duplica la alerta")
    void redeliveryDoesNotDuplicateTheAlert() {
        dosimetryEvents.save(outboxEvent(DosimetryOutboxRelay.WARNING_RAISED));

        relay().drainOnce();
        relay().drainOnce();

        assertThat(alerts.findByWorkerIdOrderByCreatedAtDesc(workerId)).hasSize(1);
    }

    // ─── Escalamiento ────────────────────────────────────────────────────────

    @Test
    @DisplayName("sin acuse en la ventana, la alerta danger se escala al SSO de la organización")
    void unacknowledgedDangerAlertIsEscalatedToTheSso() {
        Alert overdue = workerAlert("danger", LocalDateTime.now().minusMinutes(5));

        assertThat(escalationJob.escalateOverdue(LocalDateTime.now())).isEqualTo(1);

        Alert escalated = alerts.findById(overdue.getId()).orElseThrow();
        assertThat(escalated.getEscalatedAt()).isNotNull();
        assertThat(escalated.getSsoResponsibleId()).isEqualTo(ssoId);
        assertThat(escalated.getEscalationReason()).contains("Escalamiento automático");
        assertThat(evidence.findByOrganizationIdAndAlertId(ORG, String.valueOf(overdue.getId()))
                .orElseThrow().isEscalatedWithoutAcknowledgement())
                .as("regla 10: escalada sin acuse").isTrue();

        assertThat(escalationJob.escalateOverdue(LocalDateTime.now()))
                .as("no se escala dos veces").isZero();
    }

    @Test
    @DisplayName("no se escalan las recientes, las acusadas, las preventivas ni las viejas")
    void onlyOverdueUnacknowledgedDangerAlertsAreEscalated() {
        workerAlert("danger", LocalDateTime.now().minusSeconds(10));
        workerAlert("warning", LocalDateTime.now().minusMinutes(5));
        Alert acknowledged = workerAlert("danger", LocalDateTime.now().minusMinutes(5));
        acknowledged.acknowledge(LocalDateTime.now().minusMinutes(4));
        alerts.save(acknowledged);
        workerAlert("danger", LocalDateTime.now().minusDays(2));

        assertThat(escalationJob.escalateOverdue(LocalDateTime.now())).isZero();
    }

    // ─── Listados y permisos ─────────────────────────────────────────────────

    @Test
    @DisplayName("el operario ve solo sus alertas y el SSO las de su organización")
    void listingsAreScopedToTheCaller() throws Exception {
        Alert mine = workerAlert("danger", LocalDateTime.now());
        workerAlertFor(otherWorkerId, OTHER_ORG);

        mvc.perform(get("/api/alarms/mine").with(user(WORKER_EMAIL).roles("WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(mine.getId()));
        mvc.perform(get("/api/alarms/organization").with(user(SSO_EMAIL).roles("SSO_RESPONSIBLE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].workerId").value(workerId));

        mvc.perform(get("/api/alarms/organization").with(user(WORKER_EMAIL).roles("WORKER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/alarms/mine").with(user(SSO_EMAIL).roles("SSO_RESPONSIBLE")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un operario no acusa la alerta de otro y un SSO no escala la de otra organización")
    void ownershipIsEnforced() throws Exception {
        Alert alert = workerAlert("danger", LocalDateTime.now());

        mvc.perform(post("/api/alarms/" + alert.getId() + "/acknowledge")
                        .with(user(OTHER_WORKER_EMAIL).roles("WORKER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alarms/" + alert.getId() + "/escalate")
                        .with(user(OTHER_SSO_EMAIL).roles("SSO_RESPONSIBLE"))
                        .contentType("application/json")
                        .content("{\"ssoResponsibleId\":%d}".formatted(ssoId)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/alarms/" + alert.getId() + "/acknowledge")
                        .with(user(WORKER_EMAIL).roles("WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acknowledgedAt").isNotEmpty());
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────

    private Alert workerAlert(String type, LocalDateTime createdAt) {
        Alert alert = Alert.builder().type(type).title("IT " + type)
                .recipientType(Alert.TO_SPECIFIC)
                .organizationId(ORG).workerId(workerId).sessionId("sess-alert")
                .createdAt(createdAt.truncatedTo(ChronoUnit.MICROS)).build();
        return alerts.save(alert);
    }

    private void workerAlertFor(Long worker, Long organization) {
        alerts.save(Alert.builder().type("danger").title("IT otra")
                .recipientType(Alert.TO_SPECIFIC)
                .organizationId(organization).workerId(worker).build());
    }

    private Long userId(String email, Role role, Long organizationId) {
        return users.findByEmail(email)
                .orElseGet(() -> users.save(AppUser.builder()
                        .email(email).name("IT " + role).initials("IT")
                        .passwordHash("{noop}irrelevant").role(role)
                        .organizationId(organizationId).status(UserStatus.ACTIVE)
                        .joinDate(LocalDate.now()).build()))
                .getId();
    }

    private DosimetryOutboxRelay relay() {
        return new DosimetryOutboxRelay(dosimetryEvents, eventPublisher, objectMapper, Clock.systemUTC());
    }

    /** El mismo payload que emite PersonalDosimetryService.ingest para los dos tipos. */
    private DosimetryEvent outboxEvent(String type) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "sessionId", "sess-alert",
                "workerId", workerId,
                "readingId", 1L,
                "accumulatedIndex", new BigDecimal("900"),
                "limitIndex", new BigDecimal("800"),
                "limitVersionId", 1L,
                "capturedAt", now.minusSeconds(30),
                "detectedAt", now,
                "methodId", "MAGNETIC_TIME_INDEX_V1",
                "unit", "uT*s"));
        payload.put("schemaVersion", 1);
        payload.put("organizationId", ORG);
        try {
            return new DosimetryEvent(ORG, type, objectMapper.writeValueAsString(payload), now);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
