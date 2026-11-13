package com.emsafe.alerts;

import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.occupationalhealth.domain.model.AlertEvidenceRecord;
import com.emsafe.occupationalhealth.infrastructure.persistence.AlertEvidenceRecordRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acuse y escalamiento de alertas de operario, y la evidencia que dejan en
 * Occupational Health (informe §5.6.3, RF25, US60).
 *
 * <p>Este era el hecho que figuraba como «{@code AlertEvidenceReported} sin
 * productor». El inbox de Occupational Health llevaba tiempo listo y correcto;
 * lo que no existía era la <b>función</b> en el contexto Alerts, que solo sabía
 * de alarmas dirigidas a clientes. Estos tests recorren el camino entero:
 * endpoint → agregado → evento → evidencia en el otro contexto.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:alert_evidence;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000",
        "spring.flyway.enabled=false"
})
class AlertEvidenceApiIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private AlertEvidenceRecordRepository evidence;

    private String adminToken;
    private String workerToken;
    private String ssoToken;
    private String clientToken;
    private long organizationId;
    private long workerId;
    private long ssoResponsibleId;

    @BeforeEach
    void login() throws Exception {
        // Las cuentas industriales las siembra DataInitializer.seedIndustrialUsers()
        // y van ligadas a la empresa, no al dominio emsafe.com.
        adminToken = token("admin@emsafe.com", "admin123");
        workerToken = token("operario1@quantumdyn.com", "worker123");
        ssoToken = token("sso@quantumdyn.com", "sso12345");
        clientToken = token("ops@quantumdyn.com", "client123");
        var worker = users.findByEmail("operario1@quantumdyn.com").orElseThrow();
        workerId = worker.getId();
        organizationId = worker.getOrganizationId();
        ssoResponsibleId = users.findByEmail("sso@quantumdyn.com").orElseThrow().getId();
    }

    // ─── El camino completo ──────────────────────────────────────────────────

    @Test
    @DisplayName("el acuse del operario deja evidencia en Occupational Health")
    void acknowledgementLeavesEvidence() throws Exception {
        long alertId = createWorkerAlert();

        postAs(workerToken, "/api/alarms/" + alertId + "/acknowledge", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acknowledgedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.escalatedAt").doesNotExist());

        AlertEvidenceRecord record = evidence
                .findByOrganizationIdAndAlertId(organizationId, String.valueOf(alertId)).orElseThrow();
        assertThat(record.getAcknowledgedAt()).isNotNull();
        assertThat(record.getWorkerId()).isEqualTo(workerId);
        assertThat(record.getEscalatedAt()).isNull();
        assertThat(record.isEscalatedWithoutAcknowledgement()).isFalse();
    }

    /**
     * El caso que de verdad importa de los dos: un escalamiento SIN acuse previo
     * es la condición exacta de la regla 10, la que sube la prioridad de la
     * propuesta del operario. Por eso escalar no exige acusar antes.
     */
    @Test
    @DisplayName("escalar sin acuse previo queda registrado como tal")
    void escalationWithoutAcknowledgementIsRecordedAsSuch() throws Exception {
        long alertId = createWorkerAlert();

        postAs(ssoToken, "/api/alarms/" + alertId + "/escalate",
                "{\"ssoResponsibleId\":%d,\"reason\":\"sin respuesta en 15 min\"}".formatted(ssoResponsibleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.escalatedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.escalationReason").value("sin respuesta en 15 min"));

        AlertEvidenceRecord record = evidence
                .findByOrganizationIdAndAlertId(organizationId, String.valueOf(alertId)).orElseThrow();
        assertThat(record.isEscalatedWithoutAcknowledgement())
                .as("es la condicion de la regla 10").isTrue();
    }

    @Test
    @DisplayName("acusar dos veces no mueve la fecha ni duplica la evidencia")
    void acknowledgingTwiceIsIdempotent() throws Exception {
        long alertId = createWorkerAlert();

        String first = postAs(workerToken, "/api/alarms/" + alertId + "/acknowledge", null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = postAs(workerToken, "/api/alarms/" + alertId + "/acknowledge", null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.data.acknowledgedAt"))
                .as("la marca es evidencia: moverla falsearia cuando se entero el operario")
                .isEqualTo(JsonPath.read(first, "$.data.acknowledgedAt"));
        assertThat(evidence.findAll().stream()
                .filter(r -> r.getAlertId().equals(String.valueOf(alertId))).count()).isEqualTo(1);
    }

    // ─── Lo que no se puede acusar ───────────────────────────────────────────

    @Test
    @DisplayName("una alarma de cliente no se puede acusar ni escalar")
    void aClientAlarmCannotBeAcknowledged() throws Exception {
        long alertId = createAlarm("""
                {"type":"info","title":"Mantenimiento programado","recipientType":"all"}""");

        postAs(adminToken, "/api/alarms/" + alertId + "/acknowledge", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        postAs(adminToken, "/api/alarms/" + alertId + "/escalate",
                "{\"ssoResponsibleId\":1}").andExpect(status().isBadRequest());
    }

    /**
     * Sin organización la marca se sellaría aquí y la evidencia fallaría al otro
     * lado, donde el fallo no se propaga: se perdería en silencio. Se niega antes.
     */
    @Test
    @DisplayName("una alerta de operario sin organizationId se rechaza, no se pierde en silencio")
    void aWorkerAlertWithoutOrganizationIsRejected() throws Exception {
        long alertId = createAlarm("""
                {"type":"danger","title":"Exposicion alta","recipientType":"all","workerId":%d}"""
                .formatted(workerId));

        postAs(adminToken, "/api/alarms/" + alertId + "/acknowledge", null)
                .andExpect(status().isBadRequest());
        assertThat(evidence.findAll().stream()
                .anyMatch(r -> r.getAlertId().equals(String.valueOf(alertId)))).isFalse();
    }

    // ─── Quién puede hacer qué ───────────────────────────────────────────────

    @Test
    @DisplayName("acusa el operario y escala el SSO; el cliente no hace ninguna de las dos")
    void rolesAreEnforced() throws Exception {
        long alertId = createWorkerAlert();
        String escalation = "{\"ssoResponsibleId\":%d}".formatted(ssoResponsibleId);

        // El cliente queda fuera de las dos: sin estas reglas, /api/alarms/**
        // caería en el anyRequest() y solo entraría ADMIN.
        postAs(clientToken, "/api/alarms/" + alertId + "/acknowledge", null)
                .andExpect(status().isForbidden());
        postAs(clientToken, "/api/alarms/" + alertId + "/escalate", escalation)
                .andExpect(status().isForbidden());
        // Cada rol hace lo suyo y no lo del otro.
        postAs(ssoToken, "/api/alarms/" + alertId + "/acknowledge", null)
                .andExpect(status().isForbidden());
        postAs(workerToken, "/api/alarms/" + alertId + "/escalate", escalation)
                .andExpect(status().isForbidden());
        postAs(null, "/api/alarms/" + alertId + "/acknowledge", null)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("escalar sin ssoResponsibleId es 400")
    void escalatingWithoutAnSsoResponsibleIs400() throws Exception {
        long alertId = createWorkerAlert();

        postAs(ssoToken, "/api/alarms/" + alertId + "/escalate", "{}")
                .andExpect(status().isBadRequest());
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────

    private long createWorkerAlert() throws Exception {
        return createAlarm("""
                {"type":"danger","title":"Exposicion alta","recipientType":"all",
                 "organizationId":%d,"workerId":%d,"sessionId":"S-evidence-1"}"""
                .formatted(organizationId, workerId));
    }

    private long createAlarm(String json) throws Exception {
        String body = postAs(adminToken, "/api/alarms", json)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.id")).longValue();
    }

    private ResultActions postAs(String token, String path, String json) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON);
        if (json != null) request.content(json);
        if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return mvc.perform(request);
    }

    private String token(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }
}
