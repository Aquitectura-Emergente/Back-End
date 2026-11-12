package com.emsafe.aiforecasting;

import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fase 5: la API de AI Forecasting de punta a punta con SecurityConfig, filtro JWT y tokens
 * reales obtenidos por {@code POST /api/auth/login} con los usuarios de DataInitializer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:af_integration;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000",
        "spring.flyway.enabled=false"
})
class AiForecastingApiIntegrationTest {

    private static final String BASE = "/api/v1/ai-forecasting";
    private static final String METHOD = "MAGNETIC_TIME_INDEX_V1";

    /** Espejo de RotationSuggestedInput del inbox de Occupational Health (feature/occupational-health-mitigation). */
    record OccupationalHealthRotationSuggestedInput(Long organizationId, Long workerId, String suggestionId,
                                                    List<Long> candidateWorkerIds,
                                                    LocalDateTime projectedExceedanceAt, String criticality,
                                                    String rationale) {
    }

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    /** workerId únicos por test: en datos reales son ids de IAM y no se repiten entre organizaciones. */
    private final int base = ThreadLocalRandom.current().nextInt(1_000, 9_000) * 1_000;
    private final int w101 = base + 101, w200 = base + 200, w300 = base + 300;
    private String adminToken;
    private String companyToken;
    private String otherCompanyToken;
    private String technicianToken;
    private long companyOrg;
    private long otherCompanyOrg;

    @BeforeEach
    void login() throws Exception {
        adminToken = token("admin@emsafe.com", "admin123");
        companyToken = token("ops@quantumdyn.com", "client123");
        otherCompanyToken = token("facilities@harbormed.com", "client123");
        technicianToken = token("marcus@emsafe.com", "tech123");
        companyOrg = users.findByEmail("ops@quantumdyn.com").orElseThrow().getId();
        otherCompanyOrg = users.findByEmail("facilities@harbormed.com").orElseThrow().getId();
    }

    private String token(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private ResultActions postAs(String token, String path, String json) throws Exception {
        MockHttpServletRequestBuilder request = post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(token == null ? request : bearer(request, token));
    }

    private ResultActions getAs(String token, String path, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(BASE + path);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(token == null ? request : bearer(request, token));
    }

    private String snapshot(String eventId, long org, long worker, long position, String index) {
        return """
                {"eventId":"%s","organizationId":%d,"workerId":%d,"sessionId":"S-%s-%d","zoneId":"Z-%s",
                 "positionId":%d,"accumulatedIndex":%s,"limitIndex":12000,
                 "sessionStartedAt":"2026-11-10T08:00:00Z","plannedShiftEnd":"2026-11-10T16:00:00Z",
                 "capturedAt":"2026-11-10T12:00:00Z"}
                """.formatted(eventId, org, worker, run, worker, run, position, index);
    }

    private String warning(String eventId, long org, long worker, String index, String method) {
        return """
                {"eventId":"%s","correlationId":"corr-%s","organizationId":%d,"sessionId":"S-%s-%d",
                 "workerId":%d,"accumulatedIndex":%s,"limitIndex":12000,"limitVersionId":7,
                 "capturedAt":"2026-11-10T12:00:00Z","methodId":"%s","unit":"uT*s"}
                """.formatted(eventId, run, org, run, worker, worker, index, method);
    }

    /**
     * Zona propia del run: 101 (puesto 10, tasa 0.5) → HIGH con rotación hacia 200;
     * 300 (puesto 30, casi en el límite, sin candidato viable) → CRITICAL; 200 → LOW.
     */
    private String seedScenario(long org) throws Exception {
        postAs(adminToken, "/inbox/exposure-snapshot", snapshot("s101-" + run, org, w101, 10, "6000"))
                .andExpect(status().isOk());
        postAs(adminToken, "/inbox/exposure-snapshot", snapshot("s200-" + run, org, w200, 20, "1440"))
                .andExpect(status().isOk());
        String body = postAs(adminToken, "/inbox/exposure-warning-raised", warning("w101-" + run, org, w101, "7200", METHOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        // 300 entra a la zona después, así no es candidato de 101; para él nadie es viable.
        postAs(adminToken, "/inbox/exposure-snapshot", snapshot("s300-" + run, org, w300, 30, "11000"))
                .andExpect(status().isOk());
        postAs(adminToken, "/inbox/exposure-warning-raised", warning("w300-" + run, org, w300, "11500", METHOD))
                .andExpect(status().isOk());
        postAs(adminToken, "/inbox/exposure-warning-raised", warning("w200-" + run, org, w200, "1440", METHOD))
                .andExpect(status().isOk());
        return JsonPath.read(body, "$.data.assessmentId");
    }

    private static long randomOrg() {
        return ThreadLocalRandom.current().nextLong(1_000_000, 9_000_000);
    }

    // ─── Autenticación ───────────────────────────────────────────────────────

    @Test
    void withoutTokenEveryEndpointIs403() throws Exception {
        postAs(null, "/inbox/exposure-snapshot", snapshot("s-" + run, 1, 1, 1, "1")).andExpect(status().isForbidden());
        postAs(null, "/inbox/exposure-warning-raised", warning("w-" + run, 1, 1, "1", METHOD))
                .andExpect(status().isForbidden());
        postAs(null, "/inbox/shift-updated", "{}").andExpect(status().isForbidden());
        getAs(null, "/assessments/" + UUID.randomUUID()).andExpect(status().isForbidden());
        getAs(null, "/assessments").andExpect(status().isForbidden());
        getAs(null, "/projections/workers/101").andExpect(status().isForbidden());
        getAs(null, "/projections/critical-workers", "organizationId", "1").andExpect(status().isForbidden());
        getAs(null, "/outbox").andExpect(status().isForbidden());
        postAs(null, "/outbox/x/ack", "").andExpect(status().isForbidden());
    }

    @Test
    void tamperedTokenIs403() throws Exception {
        String tampered = adminToken.substring(0, adminToken.length() - 4) + "abcd";

        getAs(tampered, "/assessments").andExpect(status().isForbidden());
    }

    @Test
    void otherContextsUnderApiV1RemainPublic() throws Exception {
        // La regla nueva es solo para /api/v1/ai-forecasting/**: el resto de /api/v1 sigue permitAll.
        mvc.perform(get("/api/v1/readings").param("limit", "1")).andExpect(status().isOk());
    }

    // ─── Autorización ────────────────────────────────────────────────────────

    @Test
    void nonAdminWithInvalidBodyInInboxIs403NotValidation() throws Exception {
        String invalid = "{\"eventId\":\"\",\"accumulatedIndex\":1.1234567}";

        for (String token : List.of(companyToken, technicianToken)) {
            postAs(token, "/inbox/exposure-snapshot", invalid).andExpect(status().isForbidden());
            postAs(token, "/inbox/exposure-warning-raised", invalid).andExpect(status().isForbidden());
            postAs(token, "/inbox/shift-updated", invalid).andExpect(status().isForbidden());
            postAs(token, "/inbox/shift-updated", "{not json").andExpect(status().isForbidden());
        }
    }

    @Test
    void nonAdminCannotUseOutbox() throws Exception {
        getAs(companyToken, "/outbox").andExpect(status().isForbidden());
        postAs(companyToken, "/outbox/x/ack", "").andExpect(status().isForbidden());
        getAs(technicianToken, "/outbox").andExpect(status().isForbidden());
    }

    @Test
    void clientOfAnotherOrganizationIs403AndOwnerCanRead() throws Exception {
        String id = seedScenario(companyOrg);

        // dueña
        getAs(companyToken, "/assessments/" + id).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationId").value(companyOrg));
        getAs(companyToken, "/projections/workers/" + w101).andExpect(status().isOk());
        getAs(companyToken, "/projections/critical-workers").andExpect(status().isOk());
        getAs(companyToken, "/assessments", "sessionId", "S-" + run + "-" + w101)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));

        // otra organización
        getAs(otherCompanyToken, "/assessments/" + id).andExpect(status().isForbidden());
        getAs(otherCompanyToken, "/projections/critical-workers", "organizationId", String.valueOf(companyOrg))
                .andExpect(status().isForbidden());
        getAs(otherCompanyToken, "/assessments", "organizationId", String.valueOf(companyOrg))
                .andExpect(status().isForbidden());
        // sin organizationId, la otra empresa solo ve lo suyo: nada de este run
        getAs(otherCompanyToken, "/assessments", "sessionId", "S-" + run + "-" + w101)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        assertThat(otherCompanyOrg).isNotEqualTo(companyOrg);

        // técnico
        getAs(technicianToken, "/assessments/" + id).andExpect(status().isForbidden());
    }

    // ─── Flujo funcional (ADMIN) ─────────────────────────────────────────────

    @Test
    void adminFlowAssessmentIsQueryableAndCriticalWorkersAreSorted() throws Exception {
        long org = randomOrg();
        String id = seedScenario(org);

        getAs(adminToken, "/assessments/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECOMMENDED"))
                .andExpect(jsonPath("$.data.criticality").value("HIGH"))
                .andExpect(jsonPath("$.data.isProjection").value(true))
                .andExpect(jsonPath("$.data.projection.estimatedExceedanceAt").value("2026-11-10T14:40:00Z"))
                .andExpect(jsonPath("$.data.recommendation.type").value("ROTATION"))
                .andExpect(jsonPath("$.data.recommendation.targetWorkerId").value(w200));

        getAs(adminToken, "/projections/critical-workers", "organizationId", String.valueOf(org))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].workerId", contains(w300, w101, w200)))
                .andExpect(jsonPath("$.data.content[*].criticality", contains("CRITICAL", "HIGH", "LOW")))
                .andExpect(jsonPath("$.data.content[1].recommendation.type").value("ROTATION"))
                .andExpect(jsonPath("$.data.content[0].recommendation.type").value("RESCHEDULE_TASK"));

        getAs(adminToken, "/projections/critical-workers", "organizationId", String.valueOf(org),
                "minCriticality", "HIGH")
                .andExpect(jsonPath("$.data.content[*].workerId", contains(w300, w101)));
    }

    @Test
    void outboxCarriesRotationSuggestedWithTheOccupationalHealthContract() throws Exception {
        long org = randomOrg();
        String id = seedScenario(org);

        String body = getAs(adminToken, "/outbox", "size", "200").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rotation = JsonPath.read(body,
                "$.data.content[?(@.aggregateId == '" + id + "' && @.eventType == 'RotationSuggested')]");
        assertThat(rotation).hasSize(1);
        Map<String, Object> event = rotation.get(0);
        assertThat(event).containsEntry("schemaVersion", 1)
                .containsEntry("causationId", "w101-" + run)
                .containsEntry("correlationId", "corr-" + run)
                .containsKeys("eventId", "occurredAt");
        assertThat(((Number) event.get("organizationId")).longValue()).isEqualTo(org);

        // El payload se deserializa sin campos desconocidos en el DTO de Occupational Health.
        ObjectMapper strict = new ObjectMapper().registerModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        OccupationalHealthRotationSuggestedInput input = strict.convertValue(event.get("payload"),
                OccupationalHealthRotationSuggestedInput.class);
        assertThat(input.organizationId()).isEqualTo(org);
        assertThat(input.workerId()).isEqualTo((long) w101);
        assertThat(input.suggestionId()).isEqualTo(id);
        assertThat(input.candidateWorkerIds()).containsExactly((long) w200);
        assertThat(input.projectedExceedanceAt()).isEqualTo(LocalDateTime.parse("2026-11-10T14:40:00"));
        assertThat(input.criticality()).isEqualTo("HIGH");
        assertThat(input.rationale()).isNotBlank().contains(String.valueOf(w101));

        List<Object> risk = JsonPath.read(body,
                "$.data.content[?(@.aggregateId == '" + id + "' && @.eventType == 'PredictiveRiskDetected')]"
                        + ".payload.isProjection");
        assertThat(risk).containsExactly(true);
    }

    @Test
    void ackRemovesEventFromPendingAndIsIdempotent() throws Exception {
        String id = seedScenario(randomOrg());
        String body = getAs(adminToken, "/outbox", "size", "200").andReturn().getResponse().getContentAsString();
        String eventId = JsonPath.<List<String>>read(body,
                "$.data.content[?(@.aggregateId == '" + id + "')].eventId").get(0);

        String first = postAs(adminToken, "/outbox/" + eventId + "/ack", "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = postAs(adminToken, "/outbox/" + eventId + "/ack", "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.data.publishedAt"))
                .isEqualTo(JsonPath.read(first, "$.data.publishedAt"));
        getAs(adminToken, "/outbox", "size", "200")
                .andExpect(jsonPath("$.data.content[?(@.eventId == '" + eventId + "')]", empty()));
    }

    @Test
    void duplicateEventCreatesNoDuplicates() throws Exception {
        long org = randomOrg();
        String id = seedScenario(org);
        String before = getAs(adminToken, "/outbox", "size", "200").andReturn().getResponse().getContentAsString();
        int eventsBefore = JsonPath.<List<Object>>read(before, "$.data.content[?(@.aggregateId == '" + id + "')]").size();

        postAs(adminToken, "/inbox/exposure-warning-raised", warning("w101-" + run, org, w101, "9999", METHOD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true));
        postAs(adminToken, "/inbox/exposure-snapshot", snapshot("s101-" + run, org, w101, 10, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true));

        getAs(adminToken, "/assessments", "organizationId", String.valueOf(org), "workerId", String.valueOf(w101))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(id))
                .andExpect(jsonPath("$.data.content[0].projection.currentIndex").value(7200.0));
        String after = getAs(adminToken, "/outbox", "size", "200").andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(after, "$.data.content[?(@.aggregateId == '" + id + "')]"))
                .hasSize(eventsBefore);
    }

    // ─── Errores ─────────────────────────────────────────────────────────────

    @Test
    void unknownIdIs404() throws Exception {
        getAs(adminToken, "/assessments/" + UUID.randomUUID()).andExpect(status().isNotFound());
        getAs(adminToken, "/projections/workers/987654321").andExpect(status().isNotFound());
        postAs(adminToken, "/outbox/missing-" + run + "/ack", "").andExpect(status().isNotFound());
    }

    @Test
    void invalidBodyFromAdminIs400() throws Exception {
        postAs(adminToken, "/inbox/exposure-snapshot", "{\"eventId\":\"\",\"accumulatedIndex\":1.1234567}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        postAs(adminToken, "/inbox/exposure-warning-raised", "{not json").andExpect(status().isBadRequest());
        getAs(adminToken, "/assessments", "size", "0").andExpect(status().isBadRequest());
    }

    @Test
    void invalidDomainRuleIs422AndStateConflictIs409() throws Exception {
        long org = randomOrg();
        postAs(adminToken, "/inbox/exposure-snapshot", snapshot("s1-" + run, org, 101, 10, "100"))
                .andExpect(status().isOk());

        postAs(adminToken, "/inbox/exposure-warning-raised", warning("w1-" + run, org, 101, "200", "UNKNOWN"))
                .andExpect(status().isUnprocessableEntity());
        // captura igual al inicio de la sesión → elapsed = 0
        postAs(adminToken, "/inbox/exposure-snapshot", snapshot("s2-" + run, org, 102, 10, "0")
                .replace("\"capturedAt\":\"2026-11-10T12:00:00Z\"", "\"capturedAt\":\"2026-11-10T08:00:00Z\""))
                .andExpect(status().isOk());
        postAs(adminToken, "/inbox/exposure-warning-raised", warning("w2-" + run, org, 102, "0", METHOD)
                .replace("\"capturedAt\":\"2026-11-10T12:00:00Z\"", "\"capturedAt\":\"2026-11-10T08:00:00Z\""))
                .andExpect(status().isUnprocessableEntity());
        // aviso de una sesión sin snapshot → conflicto de estado
        postAs(adminToken, "/inbox/exposure-warning-raised", warning("w3-" + run, org, 999, "1", METHOD))
                .andExpect(status().isConflict());
    }
}
