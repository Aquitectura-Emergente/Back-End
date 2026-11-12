package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato web de la Fase 4: validación, traducción de errores, forma del JSON, política de
 * acceso y documentación OpenAPI. Usuarios de DataInitializer; la autenticación es simulada
 * con {@code @WithMockUser} (el JWT real y SecurityConfig se prueban en la Fase 5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:af_web;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000",
        "spring.flyway.enabled=false"
})
class AiForecastingControllerTest {

    private static final String BASE = "/api/v1/ai-forecasting";
    private static final String ADMIN = "admin@emsafe.com";
    private static final String COMPANY = "ops@quantumdyn.com";
    private static final String OTHER_COMPANY = "facilities@harbormed.com";
    private static final String TECHNICIAN = "marcus@emsafe.com";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;

    private long companyOrg;
    /** Cada test usa sesiones/eventos propios para no depender del orden de ejecución. */
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void setUp() {
        companyOrg = users.findByEmail(COMPANY).orElseThrow().getId();
    }

    private static long randomOrg() {
        return ThreadLocalRandom.current().nextLong(1_000_000, 9_000_000);
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String snapshot(String eventId, long org, long worker, long position, String index) {
        return """
                {"eventId":"%s","organizationId":%d,"workerId":%d,"sessionId":"S-%s-%d","zoneId":"Z-1",
                 "positionId":%d,"accumulatedIndex":%s,"limitIndex":12000,
                 "sessionStartedAt":"2026-11-10T08:00:00Z","plannedShiftEnd":"2026-11-10T16:00:00Z",
                 "capturedAt":"2026-11-10T12:00:00Z"}
                """.formatted(eventId, org, worker, run, worker, position, index);
    }

    private String warning(String eventId, long org, long worker, String index, String method) {
        return """
                {"eventId":"%s","correlationId":"corr-%s","organizationId":%d,"sessionId":"S-%s-%d",
                 "workerId":%d,"accumulatedIndex":%s,"limitIndex":12000,"limitVersionId":7,
                 "capturedAt":"2026-11-10T12:00:00Z","methodId":"%s","unit":"uT*s"}
                """.formatted(eventId, eventId, org, run, worker, worker, index, method);
    }

    /** 101 HIGH con rotación hacia 200 (LOW tras su propio aviso) en la organización indicada. */
    private String seedHigh(long org) throws Exception {
        postJson("/inbox/exposure-snapshot", snapshot("s1-" + run, org, 101, 10, "6000")).andExpect(status().isOk());
        postJson("/inbox/exposure-snapshot", snapshot("s2-" + run, org, 200, 20, "1440")).andExpect(status().isOk());
        String body = postJson("/inbox/exposure-warning-raised",
                warning("w1-" + run, org, 101, "7200", "MAGNETIC_TIME_INDEX_V1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.assessmentId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.assessmentId");
    }

    // ─── Inbox + consultas (ADMIN) ───────────────────────────────────────────

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void inboxFlowProducesAQueryableAssessment() throws Exception {
        String id = seedHigh(randomOrg());

        mvc.perform(get(BASE + "/assessments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.workerId").value(101))
                .andExpect(jsonPath("$.data.status").value("RECOMMENDED"))
                .andExpect(jsonPath("$.data.criticality").value("HIGH"))
                .andExpect(jsonPath("$.data.isProjection").value(true))
                .andExpect(jsonPath("$.data.projection.currentIndex").value(7200.0))
                .andExpect(jsonPath("$.data.projection.projectedRatio").value(1.2))
                .andExpect(jsonPath("$.data.projection.estimatedExceedanceAt").value("2026-11-10T14:40:00Z"))
                .andExpect(jsonPath("$.data.projection.unit").value("uT*s"))
                .andExpect(jsonPath("$.data.recommendation.type").value("ROTATION"))
                .andExpect(jsonPath("$.data.recommendation.targetWorkerId").value(200))
                .andExpect(jsonPath("$.data.redaction.generatedBy").value("FALLBACK"))
                .andExpect(jsonPath("$.data.redaction.text", containsString("101")))
                .andExpect(jsonPath("$.data.modelVersion.code").value("LINEAR_RATE_V1"))
                .andExpect(jsonPath("$.data.limitVersionId").value(7))
                .andExpect(jsonPath("$.data.triggerEventId").value("w1-" + run));

        mvc.perform(get(BASE + "/projections/workers/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workerId").value(101));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void duplicateEventIsOkWithoutEffects() throws Exception {
        long org = randomOrg();
        seedHigh(org);

        postJson("/inbox/exposure-warning-raised", warning("w1-" + run, org, 101, "9000", "MAGNETIC_TIME_INDEX_V1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.assessmentId").doesNotExist());

        mvc.perform(get(BASE + "/assessments").param("organizationId", String.valueOf(org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void searchAndCriticalWorkersWithMinCriticality() throws Exception {
        long org = randomOrg();
        seedHigh(org);
        postJson("/inbox/exposure-warning-raised", warning("w2-" + run, org, 200, "1440", "MAGNETIC_TIME_INDEX_V1"))
                .andExpect(status().isOk());

        mvc.perform(get(BASE + "/assessments").param("organizationId", String.valueOf(org))
                        .param("workerId", "101").param("from", "2000-01-01T00:00:00Z")
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.last").value(true));

        mvc.perform(get(BASE + "/projections/critical-workers").param("organizationId", String.valueOf(org)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].workerId", contains(101, 200)))
                .andExpect(jsonPath("$.data.content[*].criticality", contains("HIGH", "LOW")));

        mvc.perform(get(BASE + "/projections/critical-workers").param("organizationId", String.valueOf(org))
                        .param("minCriticality", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].workerId", contains(101)));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void outboxListsPendingAndAckRemovesIt() throws Exception {
        long org = randomOrg();
        String id = seedHigh(org);

        String body = mvc.perform(get(BASE + "/outbox").param("size", "200"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String eventId = JsonPath.<java.util.List<String>>read(body,
                "$.data.content[?(@.aggregateId == '" + id + "' && @.eventType == 'RotationSuggested')].eventId")
                .get(0);

        mvc.perform(get(BASE + "/outbox").param("size", "200"))
                .andExpect(jsonPath("$.data.content[?(@.eventId == '" + eventId + "')].payload.suggestionId",
                        contains(id)))
                .andExpect(jsonPath("$.data.content[?(@.eventId == '" + eventId + "')].payload.candidateWorkerIds[0]",
                        contains(200)))
                .andExpect(jsonPath("$.data.content[?(@.eventId == '" + eventId + "')].payload.projectedExceedanceAt",
                        contains("2026-11-10T14:40:00")))
                .andExpect(jsonPath("$.data.content[?(@.eventId == '" + eventId + "')].causationId",
                        contains("w1-" + run)));

        mvc.perform(post(BASE + "/outbox/" + eventId + "/ack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty());
        mvc.perform(post(BASE + "/outbox/" + eventId + "/ack")).andExpect(status().isOk());
        mvc.perform(get(BASE + "/outbox").param("size", "200"))
                .andExpect(jsonPath("$.data.content[?(@.eventId == '" + eventId + "')]", empty()));
        mvc.perform(post(BASE + "/outbox/missing-" + run + "/ack")).andExpect(status().isNotFound());
    }

    // ─── Errores ─────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void validationErrorsAre400() throws Exception {
        // sin eventId
        postJson("/inbox/exposure-snapshot", snapshot("", randomOrg(), 101, 10, "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        // más de 6 decimales
        postJson("/inbox/exposure-snapshot", snapshot("s-" + run, randomOrg(), 101, 10, "1.1234567"))
                .andExpect(status().isBadRequest());
        // JSON mal formado
        postJson("/inbox/exposure-warning-raised", "{not json").andExpect(status().isBadRequest());
        // fecha con formato inválido en el cuerpo
        postJson("/inbox/shift-updated", """
                {"eventId":"x","organizationId":1,"workerId":1,"sessionId":"S","plannedShiftEnd":"mañana"}
                """).andExpect(status().isBadRequest());
        // parámetros inválidos
        mvc.perform(get(BASE + "/assessments").param("from", "ayer")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/assessments").param("size", "201")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/projections/critical-workers").param("organizationId", "1")
                .param("minCriticality", "EXTREME")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void domainRuleViolationIs422() throws Exception {
        long org = randomOrg();
        postJson("/inbox/exposure-snapshot", snapshot("s1-" + run, org, 101, 10, "100")).andExpect(status().isOk());

        postJson("/inbox/exposure-warning-raised", warning("w1-" + run, org, 101, "200", "OTHER_METHOD"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("unsupported exposure method")));
        postJson("/inbox/exposure-snapshot", snapshot("s2-" + run, org, 102, 10, "100")
                .replace("\"limitIndex\":12000", "\"limitIndex\":0"))
                .andExpect(status().isOk());
        postJson("/inbox/exposure-warning-raised", warning("w2-" + run, org, 102, "100", "MAGNETIC_TIME_INDEX_V1")
                .replace("\"limitIndex\":12000", "\"limitIndex\":0"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void stateConflictIs409AndUnknownIs404() throws Exception {
        postJson("/inbox/exposure-warning-raised",
                warning("w1-" + run, randomOrg(), 101, "200", "MAGNETIC_TIME_INDEX_V1"))
                .andExpect(status().isConflict());
        mvc.perform(get(BASE + "/assessments/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/projections/workers/987654321")).andExpect(status().isNotFound());
    }

    // ─── Acceso ──────────────────────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    void anonymousIsForbidden() throws Exception {
        mvc.perform(get(BASE + "/assessments")).andExpect(status().isForbidden());
        postJson("/inbox/exposure-snapshot", snapshot("s-" + run, 1, 101, 10, "1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = COMPANY, roles = "CLIENT")
    void companyCannotUseInboxOrOutbox() throws Exception {
        postJson("/inbox/exposure-snapshot", snapshot("s-" + run, companyOrg, 101, 10, "1"))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/outbox")).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/outbox/x/ack")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = TECHNICIAN, roles = "TECHNICIAN")
    void technicianIsForbidden() throws Exception {
        mvc.perform(get(BASE + "/projections/critical-workers").param("organizationId", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = COMPANY, roles = "CLIENT")
    void companyReadsOnlyItsOwnOrganization() throws Exception {
        long otherOrg = users.findByEmail(OTHER_COMPANY).orElseThrow().getId();

        mvc.perform(get(BASE + "/projections/critical-workers"))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/projections/critical-workers").param("organizationId", String.valueOf(companyOrg)))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/projections/critical-workers").param("organizationId", String.valueOf(otherOrg)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/assessments").param("organizationId", String.valueOf(otherOrg)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/assessments")).andExpect(status().isOk());
    }

    // ─── OpenAPI ─────────────────────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    void allEndpointsAreDocumentedInSwagger() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['" + BASE + "/inbox/exposure-snapshot'].post.tags",
                        contains("AI Forecasting - Inbox")))
                .andExpect(jsonPath("$.paths['" + BASE + "/inbox/exposure-warning-raised'].post.summary").isNotEmpty())
                .andExpect(jsonPath("$.paths['" + BASE + "/inbox/shift-updated'].post").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/assessments/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/assessments'].get").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/projections/workers/{workerId}'].get").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/projections/critical-workers'].get.parameters[*].name",
                        hasItems("organizationId", "minCriticality", "page", "size")))
                .andExpect(jsonPath("$.paths['" + BASE + "/outbox'].get").exists())
                .andExpect(jsonPath("$.paths['" + BASE + "/outbox/{eventId}/ack'].post.summary").isNotEmpty());
    }
}
