package com.emsafe.personaldosimetry;

import com.emsafe.iam.domain.model.*;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.infrastructure.persistence.DeviceJpaRepository;
import com.emsafe.personaldosimetry.application.*;
import com.emsafe.personaldosimetry.domain.model.*;
import com.emsafe.personaldosimetry.infrastructure.persistence.*;
import com.emsafe.personaldosimetry.infrastructure.DosimetryRetentionProperties;
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

@SpringBootTest(classes = com.emsafe.EmsafeApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktbmVlZHMtMzItYnl0ZXM=",
        "jwt.expiration=3600000",
        "emsafe.personal-dosimetry.retention.cleanup-interval=PT24H"
})
abstract class PersonalDosimetryApiContract {
    protected static final String BASE = "/api/v1/personal-dosimetry";
    protected static final String SSO = "sso@pd.test", WORKER = "worker@pd.test", ADMIN = "admin@pd.test";
    protected static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    protected static final Instant START = NOW.minusSeconds(600);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PersonalDosimetryService service;
    @Autowired UserRepository users;
    @Autowired DeviceJpaRepository devices;
    @Autowired DosimetryMembershipRepository memberships;
    @Autowired OccupationalLimitRepository limits;
    @Autowired ExposureSessionRepository sessions;
    @Autowired PersonalReadingRepository readings;
    @SpyBean DosimetryEventRepository events;
    @SpyBean DosimetryAccessAuditRepository audits;
    @Autowired DosimetryRetentionService retention;
    @Autowired DosimetryAuditService auditService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired DosimetryRetentionProperties retentionPolicy;
    @MockBean DataInitializer initializer;
    @MockBean Clock clock;

    AppUser admin, company, otherCompany, worker, supervisor;
    Device device;
    private OccupationalLimit initialLimit;

    @BeforeEach
    void seed() {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        retentionPolicy.setAuditDays(0);
        retentionPolicy.setPublishedEventDays(0);
        retentionPolicy.setBatchSize(500);
        audits.deleteAll();
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

    protected DosimetryActor sso() {
        return new DosimetryActor(supervisor.getId(), false, company.getId(), MembershipRole.SSO);
    }

    protected DosimetryActor admin() {
        return new DosimetryActor(admin.getId(), true, null, null);
    }

    protected String openSession() {
        return service.openSession(sso(), new OpenSession(worker.getId(), device.getId(), 1L, START)).id();
    }

    protected IngestReading capture(long sequence, String field, long from, long to) {
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
    void deniedRequestsPersistIdentityAndResourceWithoutHeadersBodyOrQueryString() throws Exception {
        mvc.perform(get(BASE + "/workers/me/exposure").queryParam("token", "query-secret")
                        .header("Authorization", "Bearer header-secret"))
                .andExpect(status().isUnauthorized());
        var anonymous = audits.findAll().get(0);
        assertThat(anonymous.getPrincipalName()).isNull();
        assertThat(anonymous.getResourcePath()).isEqualTo(BASE + "/workers/me/exposure");
        assertThat(anonymous.getHttpMethod()).isEqualTo("GET");
        assertThat(anonymous.getResponseStatus()).isEqualTo(401);
        assertThat(anonymous.getOccurredAt()).isEqualTo(NOW);

        String id = openSession();
        postAs(WORKER, "/sessions/" + id + "/readings", capture(1, "2", 0, 1))
                .andExpect(status().isForbidden());
        var authenticated = audits.findAll().stream().filter(a -> a.getResponseStatus() == 403).findFirst().orElseThrow();
        assertThat(authenticated.getPrincipalName()).isEqualTo(WORKER);
        assertThat(authenticated.getResourcePath()).isEqualTo(BASE + "/sessions/" + id + "/readings");
        assertThat(readings.count()).isZero();
        assertThat(json.writeValueAsString(audits.findAll())).doesNotContain("query-secret", "header-secret", "fieldMicrotesla");
    }

    @Test
    void auditIsAdministrativeAndSuccessfulOrOtherContextRequestsAreNotRecorded() throws Exception {
        openSession();
        mvc.perform(get(BASE + "/workers/me/exposure").with(user(WORKER))).andExpect(status().isOk());
        // 403, no 401: el 401 está acotado a /api/v1/personal-dosimetry/** y el resto de la API
        // sigue con el Http403ForbiddenEntryPoint de siempre. Lo que verifica este test es la
        // línea siguiente — que una petición de otro contexto no se audita aquí.
        mvc.perform(get("/api/tech/work-orders")).andExpect(status().isForbidden());
        assertThat(audits.count()).isZero();
        mvc.perform(get(BASE + "/audit").with(user(SSO))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/audit").with(user(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].principalName").value(SSO));
        mvc.perform(get(BASE + "/audit?size=201").with(user(ADMIN))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void denialAuditCommitsEvenWhenAnOuterTransactionRollsBack() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            auditService.recordDenied(WORKER, "GET", BASE + "/workers/me/exposure", 403);
            transaction.setRollbackOnly();
        });
        assertThat(audits.count()).isEqualTo(1);
    }

    @Test
    void auditStorageFailureNeverAllowsTheRejectedOperation() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Simulated audit outage"))
                .when(audits).saveAndFlush(any());
        mvc.perform(get(BASE + "/workers/me/exposure").with(user("outsider@pd.test")))
                .andExpect(status().isForbidden());
        assertThat(sessions.count()).isZero();
    }

    @Test
    void retentionIsDisabledUntilAPeriodIsConfigured() {
        audits.saveAndFlush(new DosimetryAccessAudit(WORKER, "GET", BASE, 403, NOW.minusSeconds(8640000)));
        var event = new DosimetryEvent(company.getId(), "Test", "{}", NOW.minusSeconds(8640000));
        event.acknowledge(NOW.minusSeconds(8640000));
        events.saveAndFlush(event);
        retention.purgeExpired();
        assertThat(audits.count()).isEqualTo(1);
        assertThat(events.count()).isEqualTo(1);
    }

    @Test
    void retentionIsBoundedAndKeepsPendingEventsRecentAcknowledgmentsAndExposureEvidence() {
        retentionPolicy.setAuditDays(7);
        retentionPolicy.setPublishedEventDays(7);
        retentionPolicy.setBatchSize(1);
        var oldestAudit = audits.saveAndFlush(new DosimetryAccessAudit(WORKER, "GET", BASE, 403, NOW.minusSeconds(9 * 86400)));
        var expiredAudit = audits.saveAndFlush(new DosimetryAccessAudit(WORKER, "GET", BASE, 403, NOW.minusSeconds(8 * 86400)));
        var boundaryAudit = audits.saveAndFlush(new DosimetryAccessAudit(WORKER, "GET", BASE, 403, NOW.minusSeconds(7 * 86400)));
        var pending = events.saveAndFlush(new DosimetryEvent(company.getId(), "TestPending", "{}", NOW.minusSeconds(20 * 86400)));
        var oldest = new DosimetryEvent(company.getId(), "TestOldest", "{}", NOW.minusSeconds(20 * 86400));
        oldest.acknowledge(NOW.minusSeconds(9 * 86400));
        events.saveAndFlush(oldest);
        var expired = new DosimetryEvent(company.getId(), "TestExpired", "{}", NOW.minusSeconds(20 * 86400));
        expired.acknowledge(NOW.minusSeconds(8 * 86400));
        events.saveAndFlush(expired);
        var boundary = new DosimetryEvent(company.getId(), "TestBoundary", "{}", NOW.minusSeconds(20 * 86400));
        boundary.acknowledge(NOW.minusSeconds(7 * 86400));
        events.saveAndFlush(boundary);
        var recentlyAcknowledged = new DosimetryEvent(company.getId(), "TestRecentAck", "{}", NOW.minusSeconds(20 * 86400));
        recentlyAcknowledged.acknowledge(NOW);
        events.saveAndFlush(recentlyAcknowledged);
        String id = openSession();
        service.ingest(sso(), id, capture(1, "2", 0, 1));

        retention.purgeExpired();
        assertThat(audits.existsById(oldestAudit.getId())).isFalse();
        assertThat(audits.existsById(expiredAudit.getId())).isTrue();
        assertThat(events.existsById(oldest.getId())).isFalse();
        assertThat(events.existsById(expired.getId())).isTrue();
        retention.purgeExpired();
        retention.purgeExpired();
        assertThat(audits.existsById(expiredAudit.getId())).isFalse();
        assertThat(audits.existsById(boundaryAudit.getId())).isTrue();
        assertThat(events.existsById(expired.getId())).isFalse();
        assertThat(events.existsById(pending.getId())).isTrue();
        assertThat(events.existsById(boundary.getId())).isTrue();
        assertThat(events.existsById(recentlyAcknowledged.getId())).isTrue();
        assertThat(readings.count()).isEqualTo(1);
        assertThat(service.getSession(sso(), id).accumulatedIndex()).isEqualByComparingTo("2");
        assertThat(limits.count()).isEqualTo(1);
    }

    @Test
    void failedThresholdEventRollsBackReadingAndTotalsAndCanBeRetried() {
        String id = openSession();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("Simulated outbox outage"))
                .when(events).saveAndFlush(any());
        assertThatThrownBy(() -> service.ingest(sso(), id, capture(1, "101", 0, 1)))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(readings.count()).isZero();
        var unchanged = service.getSession(sso(), id);
        assertThat(unchanged.accumulatedIndex()).isZero();
        assertThat(unchanged.warningRaised()).isFalse();
        assertThat(unchanged.limitExceeded()).isFalse();
        reset(events);
        service.ingest(sso(), id, capture(1, "101", 0, 1));
        service.ingest(sso(), id, capture(1, "101", 0, 1));
        assertThat(readings.count()).isEqualTo(1);
        assertThat(events.findAll().stream().filter(e -> e.getEventType().equals("OccupationalLimitExceeded"))).hasSize(1);
    }

    @Test
    void outboxRetryWithTestConsumerDeduplicatesAfterLostAcknowledgment() {
        openSession();
        var processed = new java.util.HashSet<String>();
        int sideEffects = 0;
        var firstDelivery = service.pendingEvents(admin(), 0, 1).content().get(0);
        if (processed.add(firstDelivery.eventId())) sideEffects++;
        // Simulate a consumer crash after its effect but before /ack.
        var retry = service.pendingEvents(admin(), 0, 1).content().get(0);
        assertThat(retry.eventId()).isEqualTo(firstDelivery.eventId());
        if (processed.add(retry.eventId())) sideEffects++;
        service.acknowledgeEvent(admin(), retry.eventId());
        assertThat(sideEffects).isEqualTo(1);
        assertThat(service.pendingEvents(admin(), 0, 1).totalElements()).isZero();
    }

    @Test
    void bootIdentityIsCaseSensitive() {
        String id = openSession();
        service.ingest(sso(), id, new IngestReading(device.getId(), "Boot-A", 1L, BigDecimal.ONE, "uT", START, START.plusSeconds(1)));
        service.ingest(sso(), id, new IngestReading(device.getId(), "boot-a", 1L, BigDecimal.ONE, "uT", START.plusSeconds(1), START.plusSeconds(2)));
        assertThat(readings.count()).isEqualTo(2);
        assertThat(service.getSession(sso(), id).accumulatedIndex()).isEqualByComparingTo("2");
    }

    @Test
    void concurrentLoadKeepsExactTotalsAndOneEventPerThreshold() throws Exception {
        String id = openSession();
        int producers = 6, perProducer = 40;
        var pool = Executors.newFixedThreadPool(producers);
        var start = new CountDownLatch(1);
        var futures = new java.util.ArrayList<Future<java.util.List<Delivery>>>();
        long began = System.nanoTime();
        try {
            for (int producer = 0; producer < producers; producer++) {
                final int offset = producer * perProducer;
                futures.add(pool.submit(() -> {
                    start.await();
                    var samples = new java.util.ArrayList<Delivery>();
                    for (int i = 0; i < perProducer; i++) {
                        int sequence = offset + i;
                        var request = capture(sequence, "0.5", sequence, sequence + 1);
                        long sent = System.nanoTime();
                        boolean duplicate = service.ingest(sso(), id, request).duplicate();
                        samples.add(new Delivery(System.nanoTime() - sent, duplicate));
                        if (sequence % 3 == 0) {
                            sent = System.nanoTime();
                            duplicate = service.ingest(sso(), id, request).duplicate();
                            samples.add(new Delivery(System.nanoTime() - sent, duplicate));
                        }
                    }
                    return samples;
                }));
            }
            start.countDown();
            var samples = new java.util.ArrayList<Delivery>();
            for (var future : futures) samples.addAll(future.get(60, TimeUnit.SECONDS));
            double elapsedSeconds = (System.nanoTime() - began) / 1_000_000_000.0;
            assertThat(samples).hasSize(320);
            assertThat(samples.stream().filter(Delivery::duplicate)).hasSize(80);
            assertThat(readings.count()).isEqualTo(240);
            assertThat(service.getSession(sso(), id).accumulatedIndex()).isEqualByComparingTo("120");
            assertThat(events.findAll().stream().filter(e -> e.getEventType().equals("ExposureWarningRaised"))).hasSize(1);
            assertThat(events.findAll().stream().filter(e -> e.getEventType().equals("OccupationalLimitExceeded"))).hasSize(1);
            var sorted = samples.stream().mapToLong(Delivery::nanos).sorted().toArray();
            var report = new java.util.LinkedHashMap<String, Object>();
            report.put("test", getClass().getSimpleName());
            report.put("scope", "Concurrent service transactions on one session; not a deployment benchmark");
            report.put("producers", producers);
            report.put("uniqueReadings", 240);
            report.put("deliveries", samples.size());
            report.put("duplicates", 80);
            report.put("elapsedSeconds", elapsedSeconds);
            report.put("deliveriesPerSecond", samples.size() / elapsedSeconds);
            report.put("p50Millis", sorted[(int) Math.ceil(sorted.length * 0.50) - 1] / 1_000_000.0);
            report.put("p95Millis", sorted[(int) Math.ceil(sorted.length * 0.95) - 1] / 1_000_000.0);
            report.put("p99Millis", sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1_000_000.0);
            json.writerWithDefaultPrettyPrinter().writeValue(
                    java.nio.file.Path.of("target", "dosimetry-load-" + getClass().getSimpleName() + ".json").toFile(), report);
        } finally {
            pool.shutdownNow();
        }
    }

    private record Delivery(long nanos, boolean duplicate) {}

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
