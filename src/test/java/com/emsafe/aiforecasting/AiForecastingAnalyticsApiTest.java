package com.emsafe.aiforecasting;

import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade.ClosedSession;
import com.emsafe.plantconfiguration.domain.model.JobPosition;
import com.emsafe.plantconfiguration.domain.model.PlantZone;
import com.emsafe.plantconfiguration.infrastructure.persistence.JobPositionRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantZoneRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /analytics/organizations/{id}/summary} de punta a punta con JWT real. La fachada de
 * Personal Dosimetry se simula (su contrato se prueba en ExposureQueryFacadeTest y en
 * ExposureSessionRepositoryEndedBetweenTest); el resto (seguridad, evaluaciones, Plant
 * Configuration) es real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:af_analytics;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000",
        "spring.flyway.enabled=false"
})
class AiForecastingAnalyticsApiTest {

    private static final String BASE = "/api/v1/ai-forecasting";
    private static final String UNIT = "uT*s";
    private static final String METHOD = "MAGNETIC_TIME_INDEX_V1";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private PlantZoneRepository zones;
    @Autowired private JobPositionRepository jobPositions;
    @MockBean private ExposureQueryFacade dosimetry;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final int base = ThreadLocalRandom.current().nextInt(1_000, 9_000) * 1_000;
    private final int w101 = base + 101, w200 = base + 200, w300 = base + 300;
    private String adminToken, companyToken, otherCompanyToken, ssoToken, workerToken, technicianToken;
    private long companyOrg, otherCompanyOrg;

    @BeforeEach
    void login() throws Exception {
        adminToken = token("admin@emsafe.com", "admin123");
        companyToken = token("ops@quantumdyn.com", "client123");
        otherCompanyToken = token("facilities@harbormed.com", "client123");
        ssoToken = token("sso@quantumdyn.com", "sso12345");
        workerToken = token("operario1@quantumdyn.com", "worker123");
        technicianToken = token("marcus@emsafe.com", "tech123");
        companyOrg = users.findByEmail("ops@quantumdyn.com").orElseThrow().getId();
        otherCompanyOrg = users.findByEmail("facilities@harbormed.com").orElseThrow().getId();
        when(dosimetry.closedSessions(any(), any(), any())).thenReturn(List.of());
    }

    private String token(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }

    private ResultActions getAs(String token, String path, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(BASE + path);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions summary(String token, long org, String... params) throws Exception {
        return getAs(token, "/analytics/organizations/" + org + "/summary", params);
    }

    private void postAs(String token, String path, String json) throws Exception {
        mvc.perform(post(BASE + path).contentType(MediaType.APPLICATION_JSON).content(json)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
    }

    private String sessionId(int worker) {
        return "S-" + run + "-" + worker;
    }

    /** 101 → HIGH con rotación hacia 200; 300 → CRITICAL (escenario de la guía de Swagger). */
    private void seedAssessments(long org) throws Exception {
        String snapshot = """
                {"eventId":"%s","organizationId":%d,"workerId":%d,"sessionId":"%s","zoneId":"Z-%s",
                 "positionId":%d,"accumulatedIndex":%s,"limitIndex":12000,
                 "sessionStartedAt":"2026-11-10T08:00:00Z","plannedShiftEnd":"2026-11-10T16:00:00Z",
                 "capturedAt":"2026-11-10T12:00:00Z"}""";
        String warning = """
                {"eventId":"%s","organizationId":%d,"sessionId":"%s","workerId":%d,"accumulatedIndex":%s,
                 "limitIndex":12000,"limitVersionId":7,"capturedAt":"2026-11-10T12:00:00Z",
                 "methodId":"MAGNETIC_TIME_INDEX_V1","unit":"uT*s"}""";
        postAs(adminToken, "/inbox/exposure-snapshot",
                snapshot.formatted("s101-" + run, org, w101, sessionId(w101), run, 10, "6000"));
        postAs(adminToken, "/inbox/exposure-snapshot",
                snapshot.formatted("s200-" + run, org, w200, sessionId(w200), run, 20, "1440"));
        postAs(adminToken, "/inbox/exposure-warning-raised",
                warning.formatted("w101-" + run, org, sessionId(w101), w101, "7200"));
        postAs(adminToken, "/inbox/exposure-snapshot",
                snapshot.formatted("s300-" + run, org, w300, sessionId(w300), run, 30, "11000"));
        postAs(adminToken, "/inbox/exposure-warning-raised",
                warning.formatted("w300-" + run, org, sessionId(w300), w300, "11500"));
    }

    private static ClosedSession closed(String sessionId, long worker, long position, String index, String end) {
        BigDecimal value = new BigDecimal(index);
        Instant ended = Instant.parse(end);
        return new ClosedSession(sessionId, worker, position, value, new BigDecimal("12000"),
                new BigDecimal("0.800000"), value.compareTo(new BigDecimal("9600")) > 0,
                value.compareTo(new BigDecimal("12000")) > 0, ended.minusSeconds(8 * 3600), ended,
                ended.plusSeconds(60), new BigDecimal("100.00"), true, UNIT, METHOD);
    }

    // ─── Acceso ──────────────────────────────────────────────────────────────

    @Test
    void ownersReadTheirOrganizationAndOthersGet403() throws Exception {
        summary(adminToken, companyOrg).andExpect(status().isOk());
        summary(adminToken, otherCompanyOrg).andExpect(status().isOk());
        summary(companyToken, companyOrg).andExpect(status().isOk());
        summary(ssoToken, companyOrg).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.organizationId").value(companyOrg));

        summary(otherCompanyToken, companyOrg).andExpect(status().isForbidden());
        summary(ssoToken, otherCompanyOrg).andExpect(status().isForbidden());
        summary(workerToken, companyOrg).andExpect(status().isForbidden());
        summary(technicianToken, companyOrg).andExpect(status().isForbidden());
        summary(null, companyOrg).andExpect(status().isForbidden());
    }

    @Test
    void accessIsCheckedBeforeTheZoneAndPeriod() throws Exception {
        summary(ssoToken, otherCompanyOrg, "zone", "Mars/Olympus").andExpect(status().isForbidden());
        summary(workerToken, companyOrg, "from", "2026-11-10", "to", "2026-11-01").andExpect(status().isForbidden());
    }

    // ─── Parámetros ──────────────────────────────────────────────────────────

    @Test
    void invalidParametersAre400() throws Exception {
        summary(ssoToken, companyOrg, "zone", "Mars/Olympus").andExpect(status().isBadRequest());
        summary(ssoToken, companyOrg, "from", "2026-11-10", "to", "2026-11-01").andExpect(status().isBadRequest());
        summary(ssoToken, companyOrg, "from", "2025-01-01", "to", "2026-11-10").andExpect(status().isBadRequest());
        summary(ssoToken, companyOrg, "from", "10/11/2026").andExpect(status().isBadRequest());
        getAs(adminToken, "/analytics/organizations/abc/summary").andExpect(status().isBadRequest());
    }

    @Test
    void periodAndZoneAreInterpretedAsWholeDays() throws Exception {
        summary(adminToken, companyOrg, "from", "2026-11-02", "to", "2026-11-22")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.zone").value("America/Lima"))
                .andExpect(jsonPath("$.data.from").value("2026-11-02"))
                .andExpect(jsonPath("$.data.to").value("2026-11-22"))
                .andExpect(jsonPath("$.data.weeklyTrend[*].isoWeek", contains("2026-W45", "2026-W46", "2026-W47")));
        verify(dosimetry).closedSessions(eq(companyOrg), eq(Instant.parse("2026-11-02T05:00:00Z")),
                eq(Instant.parse("2026-11-23T05:00:00Z")));

        summary(adminToken, companyOrg, "from", "2026-11-02", "to", "2026-11-08", "zone", "UTC")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.zone").value("UTC"));
        verify(dosimetry).closedSessions(eq(companyOrg), eq(Instant.parse("2026-11-02T00:00:00Z")),
                eq(Instant.parse("2026-11-09T00:00:00Z")));
    }

    // ─── Contenido ───────────────────────────────────────────────────────────

    @Test
    void summaryOfClosedSessionsWithPositionNamesFromPlantConfiguration() throws Exception {
        PlantZone zone = zones.save(PlantZone.create(companyOrg, "Zona analítica " + run, null));
        long press = jobPositions.save(JobPosition.create(zone.getId(), "Prensa " + run, null)).getId();
        long unknown = press + 10_000;
        List<ClosedSession> sessions = new ArrayList<>(List.of(
                closed("C-1", w101, press, "11400", "2026-11-10T21:00:00Z"),
                closed("C-2", w101, press, "12300", "2026-11-17T21:00:00Z"),
                closed("C-3", w200, unknown, "3000", "2026-11-17T21:00:00Z")));
        when(dosimetry.closedSessions(any(), any(), any())).thenReturn(sessions);

        summary(ssoToken, companyOrg, "from", "2026-11-09", "to", "2026-11-22")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unit").value(UNIT))
                .andExpect(jsonPath("$.data.methodId").value(METHOD))
                .andExpect(jsonPath("$.data.isProjection").value(false))
                .andExpect(jsonPath("$.data.totals.closedSessions").value(3))
                .andExpect(jsonPath("$.data.totals.workers").value(2))
                .andExpect(jsonPath("$.data.totals.sessionsNearLimit").value(2))
                .andExpect(jsonPath("$.data.totals.sessionsNearLimitPct").value(66.67))
                .andExpect(jsonPath("$.data.totals.sessionsExceeded").value(1))
                .andExpect(jsonPath("$.data.totals.nearLimitRatio").value(0.90))
                .andExpect(jsonPath("$.data.perWorker[0].workerId").value(w101))
                .andExpect(jsonPath("$.data.perWorker[0].avgFinalIndex").value(11850.0))
                .andExpect(jsonPath("$.data.perWorker[0].maxFinalIndex").value(12300.0))
                .andExpect(jsonPath("$.data.weeklyTrend[*].sessions", contains(1, 2)))
                .andExpect(jsonPath("$.data.topPositions[0].positionId").value(press))
                .andExpect(jsonPath("$.data.topPositions[0].positionName").value("Prensa " + run))
                // Jackson del proyecto es non_null: un nombre desconocido no aparece (= null)
                .andExpect(jsonPath("$.data.topPositions[1].positionId").value(unknown))
                .andExpect(jsonPath("$.data.topPositions[1].positionName").doesNotExist())
                .andExpect(jsonPath("$.data.workersAboveWarning[0].workerId").value(w101))
                .andExpect(jsonPath("$.data.workersAboveWarning[0].sessionsAboveWarning").value(2));
    }

    @Test
    void projectedCriticalWorkersExcludeSessionsAlreadyClosed() throws Exception {
        long org = ThreadLocalRandom.current().nextLong(1_000_000, 9_000_000);
        seedAssessments(org);

        summary(adminToken, org).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectedCriticalWorkers[*].workerId", contains(w300, w101)))
                .andExpect(jsonPath("$.data.projectedCriticalWorkers[0].criticality").value("CRITICAL"))
                .andExpect(jsonPath("$.data.projectedCriticalWorkers[0].isProjection").value(true))
                .andExpect(jsonPath("$.data.projectedCriticalWorkers[1].recommendationType").value("ROTATION"))
                .andExpect(jsonPath("$.data.projectedCriticalWorkers[1].targetWorkerId").value(w200));

        when(dosimetry.closedSessions(eq(org), any(), any()))
                .thenReturn(List.of(closed(sessionId(w300), w300, 30, "11900", "2026-11-10T15:00:00Z")));

        summary(adminToken, org).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectedCriticalWorkers[*].workerId", contains(w101)));
    }
}
