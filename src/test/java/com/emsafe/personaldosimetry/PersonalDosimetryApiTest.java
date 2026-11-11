package com.emsafe.personaldosimetry;

import com.emsafe.iam.domain.model.*;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.iottelemetry.domain.model.Device;
import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.personaldosimetry.application.*;
import com.emsafe.personaldosimetry.domain.model.*;
import com.emsafe.personaldosimetry.infrastructure.persistence.*;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.*;
import com.emsafe.shared.DataInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:personal-dosimetry;DB_CLOSE_DELAY=-1;MODE=MySQL;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000"
})
class PersonalDosimetryApiTest {
    private static final String BASE = "/api/v1/personal-dosimetry";
    private static final String SSO = "sso@pd.test", WORKER = "worker@pd.test", ADMIN = "admin@pd.test";
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Instant START = NOW.minusSeconds(600);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PersonalDosimetryService service;
    @Autowired UserRepository users;
    @Autowired DeviceRepository devices;
    @Autowired DosimetryMembershipRepository memberships;
    @Autowired OccupationalLimitRepository limits;
    @Autowired ExposureSessionRepository sessions;
    @Autowired PersonalReadingRepository readings;
    @SpyBean DosimetryEventRepository events;
    @MockBean DataInitializer initializer;
    @MockBean Clock clock;

    private AppUser admin, company, otherCompany, worker, supervisor;
    private Device device;
    private OccupationalLimit initialLimit;

    @BeforeEach
    void seed() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        events.deleteAll();
        readings.deleteAll();
        sessions.deleteAll();
        limits.deleteAll();
        memberships.deleteAll();
        devices.deleteAll();
        users.deleteAll();
        admin = account(ADMIN, Role.ADMIN, false);
        company = account("company@pd.test", Role.CLIENT, true);
        otherCompany = account("other-company@pd.test", Role.CLIENT, true);
        worker = account(WORKER, Role.CLIENT, false);
        supervisor = account(SSO, Role.CLIENT, false);
        var otherSupervisor = account("other-sso@pd.test", Role.CLIENT, false);
        account("outsider@pd.test", Role.TECHNICIAN, false);
        memberships.save(new DosimetryMembership(worker.getId(), company.getId(), MembershipRole.OPERATOR, admin.getId(), NOW));
        memberships.save(new DosimetryMembership(supervisor.getId(), company.getId(), MembershipRole.SSO, admin.getId(), NOW));
        memberships.save(new DosimetryMembership(otherSupervisor.getId(), otherCompany.getId(), MembershipRole.SSO, admin.getId(), NOW));
        device = devices.saveAndFlush(Device.builder().name("Personal dosimeter").type("dosimeter")
                .serialNumber("PD-001").client(company).status("active").createdAt(LocalDateTime.now()).build());
        initialLimit = limits.saveAndFlush(new OccupationalLimit(company.getId(), 1L, 1, new BigDecimal("100"),
                new BigDecimal("0.8"), START.minusSeconds(3600), "DEMO-INDEX", admin.getId(), START.minusSeconds(3600)));
    }

    private AppUser account(String email, Role role, boolean company) {
        return users.saveAndFlush(AppUser.builder().email(email).passwordHash("not-used-by-mock-authentication")
                .name(email).role(role).clientType(company ? "company" : "person").status("active").build());
    }

    private DosimetryActor sso() {
        return new DosimetryActor(supervisor.getId(), false, company.getId(), MembershipRole.SSO);
    }

    private DosimetryActor admin() {
        return new DosimetryActor(admin.getId(), true, null, null);
    }

    private String openSession() {
        return service.openSession(sso(), new OpenSession(worker.getId(), device.getId(), 1L, START)).id();
    }

    private IngestReading capture(long sequence, String field, long from, long to) {
        return new IngestReading(device.getId(), "boot-1", sequence, new BigDecimal(field), "uT",
                START.plusSeconds(from), START.plusSeconds(to));
    }

    private ResultActions postAs(String email, String path, Object body) throws Exception {
        return mvc.perform(post(BASE + path).with(user(email)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @Test
    void unauthenticatedDosimetryIsRejectedButExistingTelemetryRemainsAvailable() throws Exception {
        mvc.perform(get(BASE + "/workers/me/exposure")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/readings")).andExpect(status().isOk());
    }

    @Test
    void unassignedPlatformAccountCannotReadExposure() throws Exception {
        mvc.perform(get(BASE + "/workers/me/exposure").with(user("outsider@pd.test")))
                .andExpect(status().isForbidden());
    }

    @Test
    void httpWorkflowCalculatesCrossingsAndPreservesClosedSnapshotForLateReadings() throws Exception {
        String response = postAs(SSO, "/sessions",
                new OpenSession(worker.getId(), device.getId(), 1L, START))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("data").path("id").asText();

        postAs(SSO, "/sessions/" + id + "/readings", capture(1, "10", 0, 8))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.session.warningRaised").value(false));
        postAs(SSO, "/sessions/" + id + "/readings", capture(2, "0.1", 8, 9))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.session.warningRaised").value(true));
        postAs(SSO, "/sessions/" + id + "/readings", capture(3, "20", 9, 10))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.session.limitExceeded").value(true));
        postAs(SSO, "/sessions/" + id + "/readings", capture(2, "0.100000", 8, 9))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(true));
        assertThat(readings.count()).isEqualTo(3);
        assertThat(events.findAll().stream().filter(e -> e.getEventType().equals("ExposureWarningRaised")).count()).isEqualTo(1);
        assertThat(events.findAll().stream().filter(e -> e.getEventType().equals("OccupationalLimitExceeded")).count()).isEqualTo(1);

        postAs(SSO, "/sessions/" + id + "/close", new CloseSession(START.plusSeconds(10)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("CLOSING"));
        postAs(SSO, "/sessions/" + id + "/finalize", new FinalizeSession(true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.coverageComplete").value(true));
        postAs(SSO, "/sessions/" + id + "/readings", capture(99, "99", 0, 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reading.status").value("LATE"))
                .andExpect(jsonPath("$.data.session.state").value("CLOSED"))
                .andExpect(jsonPath("$.data.session.accumulatedIndex").value(100.1));
        mvc.perform(get(BASE + "/workers/me/exposure").with(user(WORKER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accumulatedIndex").value(100.1));
        mvc.perform(get(BASE + "/workers/" + worker.getId() + "/sessions").with(user(WORKER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void operatorsAndSupervisorsCannotReadAnotherCompany() throws Exception {
        String id = openSession();
        mvc.perform(get(BASE + "/sessions/" + id).with(user("other-sso@pd.test")))
                .andExpect(status().isForbidden());
        postAs(WORKER, "/sessions/" + id + "/readings", capture(1, "1", 0, 1))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/sessions/" + id).with(user(WORKER))).andExpect(status().isOk());
    }

    @Test
    void onlyAdministratorCanGrantMembershipAndCannotSilentlyReassignAWorker() throws Exception {
        var candidate = account("new-worker@pd.test", Role.CLIENT, false);
        var request = new GrantMembership(candidate.getId(), company.getId(), MembershipRole.OPERATOR);
        postAs(SSO, "/memberships", request).andExpect(status().isForbidden());
        postAs(ADMIN, "/memberships", request).andExpect(status().isCreated());
        postAs(ADMIN, "/memberships", request).andExpect(status().isCreated());
        assertThat(events.count()).isEqualTo(1);
        postAs(ADMIN, "/memberships",
                new GrantMembership(candidate.getId(), otherCompany.getId(), MembershipRole.OPERATOR))
                .andExpect(status().isConflict());
    }

    @Test
    void workerAndDosimeterMustBelongToSameCompany() throws Exception {
        var otherWorker = account("other-worker@pd.test", Role.CLIENT, false);
        memberships.save(new DosimetryMembership(otherWorker.getId(), otherCompany.getId(), MembershipRole.OPERATOR,
                admin.getId(), NOW));
        postAs(SSO, "/sessions", new OpenSession(otherWorker.getId(), device.getId(), 1L, START))
                .andExpect(status().isUnprocessableEntity());
        assertThat(sessions.count()).isZero();
    }

    @Test
    void aLimitChangeAppliesToNextSessionWithoutRewritingTheCurrentOne() throws Exception {
        String id = openSession();
        postAs(SSO, "/organizations/" + company.getId() + "/limits",
                new ConfigureLimit(1L, new BigDecimal("200"), new BigDecimal("0.8"),
                        START.plusSeconds(1), "DEMO-INDEX-V2"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.version").value(2));
        assertThat(service.getSession(sso(), id).limitIndex()).isEqualByComparingTo("100");
        assertThat(service.getSession(sso(), id).limitVersionId()).isEqualTo(initialLimit.getId());
        service.requestClose(sso(), id, new CloseSession(START.plusSeconds(10)));
        var next = service.openSession(sso(), new OpenSession(worker.getId(), device.getId(), 1L, START.plusSeconds(11)));
        assertThat(next.limitIndex()).isEqualByComparingTo("200");
    }

    @Test
    void repeatedOrOverlappingCapturesNeverIncreaseAccumulatedExposureTwice() throws Exception {
        String id = openSession();
        service.ingest(sso(), id, capture(1, "2", 0, 5));
        postAs(SSO, "/sessions/" + id + "/readings", capture(1, "3", 0, 5))
                .andExpect(status().isConflict());
        postAs(SSO, "/sessions/" + id + "/readings", capture(2, "2", 4, 6))
                .andExpect(status().isConflict());
        assertThat(service.getSession(sso(), id).accumulatedIndex()).isEqualByComparingTo("10");
        assertThat(readings.count()).isEqualTo(1);
    }

    @Test
    void outOfOrderDisjointReadingsProduceTheSameExactAccumulation() {
        String id = openSession();
        service.ingest(sso(), id, capture(2, "0.1", 1, 2));
        service.ingest(sso(), id, capture(1, "0.2", 0, 1));
        assertThat(service.getSession(sso(), id).accumulatedIndex()).isEqualByComparingTo("0.3");
    }

    @Test
    void validationRejectsWrongUnitsBadIntervalsAndPrecisionLoss() throws Exception {
        String id = openSession();
        postAs(SSO, "/sessions/" + id + "/readings",
                new IngestReading(device.getId(), "boot", 1L, BigDecimal.ONE, "mT", START, START.plusSeconds(1)))
                .andExpect(status().isBadRequest());
        postAs(SSO, "/sessions/" + id + "/readings", capture(2, "1", 0, 0))
                .andExpect(status().isUnprocessableEntity());
        postAs(SSO, "/sessions/" + id + "/readings",
                new IngestReading(device.getId(), "boot", 3L, BigDecimal.ONE, "uT", START, START.plusNanos(1)))
                .andExpect(status().isUnprocessableEntity());
        postAs(SSO, "/sessions/" + id + "/readings", capture(4, "0.0000001", 0, 1))
                .andExpect(status().isUnprocessableEntity());
        assertThat(readings.count()).isZero();
    }

    @Test
    void invalidJsonReturns400AndNotAnInternalServerError() throws Exception {
        mvc.perform(post(BASE + "/sessions").with(user(SSO)).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sequenceIsRequiredAndCannotDefaultSilentlyToZero() throws Exception {
        String id = openSession();
        postAs(SSO, "/sessions/" + id + "/readings",
                new IngestReading(device.getId(), "boot", null, BigDecimal.ONE, "uT", START, START.plusSeconds(1)))
                .andExpect(status().isBadRequest());
        assertThat(readings.count()).isZero();
    }

    @Test
    void inactiveCompanyCannotOpenNewSessions() throws Exception {
        company.setStatus("inactive");
        users.saveAndFlush(company);
        postAs(SSO, "/sessions", new OpenSession(worker.getId(), device.getId(), 1L, START))
                .andExpect(status().isUnprocessableEntity());
        assertThat(sessions.count()).isZero();
    }

    @Test
    void closingAdmitsOfflineReadingsAndRequiresReconciliationBeforeFinalizing() throws Exception {
        String id = openSession();
        service.requestClose(sso(), id, new CloseSession(START.plusSeconds(100)));
        service.ingest(sso(), id, capture(1, "1", 0, 1));
        postAs(SSO, "/sessions/" + id + "/finalize", new FinalizeSession(false))
                .andExpect(status().isConflict());
        when(clock.instant()).thenReturn(START.plusSeconds(1900));
        var result = service.finalizeSession(sso(), id, new FinalizeSession(false));
        assertThat(result.state()).isEqualTo(ExposureSession.State.CLOSED);
        assertThat(result.coverageComplete()).isFalse();
        assertThat(result.coveragePercentage()).isEqualByComparingTo("1");
    }

    @Test
    void concurrentDuplicateDeliveryCountsOnlyOnce() throws Exception {
        String id = openSession();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Boolean> call = () -> {
                start.await();
                return service.ingest(sso(), id, capture(1, "2", 0, 1)).duplicate();
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS) ^ b.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(readings.count()).isEqualTo(1);
            assertThat(service.getSession(sso(), id).accumulatedIndex()).isEqualByComparingTo("2");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentOpeningCannotAssignTheWorkerOrDosimeterTwice() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Boolean> call = () -> {
                start.await();
                try {
                    openSession();
                    return true;
                } catch (DosimetryRuleException | org.springframework.dao.DataIntegrityViolationException ex) {
                    return false;
                }
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS) ^ b.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(sessions.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void outboxFailureRollsBackSessionCreation() throws Exception {
        doThrow(new IllegalStateException("Simulated outbox failure")).when(events).saveAndFlush(any());
        postAs(SSO, "/sessions", new OpenSession(worker.getId(), device.getId(), 1L, START))
                .andExpect(status().isInternalServerError());
        assertThat(sessions.count()).isZero();
    }

    @Test
    void outboxIsAdministrativeAndAcknowledgmentIsIdempotent() throws Exception {
        openSession();
        mvc.perform(get(BASE + "/outbox").with(user(SSO))).andExpect(status().isForbidden());
        String response = mvc.perform(get(BASE + "/outbox").with(user(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();
        String eventId = json.readTree(response).path("data").path("content").get(0).path("eventId").asText();
        postAs(ADMIN, "/outbox/" + eventId + "/ack", null).andExpect(status().isNoContent());
        when(clock.instant()).thenReturn(NOW.plusSeconds(2));
        postAs(ADMIN, "/outbox/" + eventId + "/ack", null).andExpect(status().isNoContent());
        assertThat(events.findById(eventId).orElseThrow().getPublishedAt()).isEqualTo(NOW);
        assertThat(service.pendingEvents(admin(), 0, 20).totalElements()).isZero();
    }
}
