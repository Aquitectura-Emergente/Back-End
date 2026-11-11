package com.emsafe.aiforecasting.infrastructure.persistence;

import com.emsafe.aiforecasting.domain.model.AssessmentStatus;
import com.emsafe.aiforecasting.domain.model.CloseReason;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ModelVersion;
import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.ports.AssessmentSearchCriteria;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.aiforecasting.domain.ports.PageResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
// Igual que en la app: traduce las excepciones JPA de los beans @Repository a DataAccessException.
@ImportAutoConfiguration(PersistenceExceptionTranslationAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAssessmentRepositoryAdapter.class, JpaWorkerSnapshotRepositoryAdapter.class,
        JpaForecastingOutboxAdapter.class, JpaProcessedMessageAdapter.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:af_persistence;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false"
})
class AiForecastingPersistenceTest {

    private static final Instant DAY = Instant.parse("2026-11-10T00:00:00Z");
    private static final ModelVersion MODEL = new ModelVersion("LINEAR_RATE", "1");

    @Autowired private JpaAssessmentRepositoryAdapter assessments;
    @Autowired private JpaWorkerSnapshotRepositoryAdapter snapshots;
    @Autowired private JpaForecastingOutboxAdapter outbox;
    @Autowired private JpaProcessedMessageAdapter processed;

    private static Instant at(int hour, int minute) {
        return DAY.plusSeconds(hour * 3600L + minute * 60L);
    }

    private static BigDecimal dec(String v) {
        return new BigDecimal(v);
    }

    private static ExposureProjection projection(Instant exceedance) {
        return new ExposureProjection(dec("7200.000000"), dec("12000.000000"), dec("0.500000"),
                dec("14400.000000"), dec("4800.000000"), dec("0.600000"), dec("1.200000"), exceedance, at(16, 0));
    }

    private static PreventiveAssessment assessed(long workerId, String session, Instant assessedAt,
                                                 CriticalityLevel level) {
        PreventiveAssessment a = PreventiveAssessment.project(UUID.randomUUID().toString(), 1L, workerId, session,
                projection(at(14, 40)), MODEL, 7L, "evt-" + UUID.randomUUID(), assessedAt);
        a.assess(level);
        return a;
    }

    // ─── Assessments ─────────────────────────────────────────────────────────

    @Test
    void assessmentRoundTripKeepsEveryField() {
        PreventiveAssessment a = assessed(101L, "S-101", at(12, 0), CriticalityLevel.HIGH);
        RotationRecommendation rec = new RotationRecommendation(RecommendationType.ROTATION, 101L, 200L, 10L, 20L,
                dec("5760.000000"), "Intercambiar con 200");
        a.recommend(rec);
        NaturalLanguageRedaction red = new NaturalLanguageRedaction("Texto", RedactionSource.LLM, at(12, 1));
        a.attachRedaction(red);
        assessments.save(a);
        a.close(CloseReason.SESSION_ENDED, at(16, 0));
        assessments.save(a);

        PreventiveAssessment loaded = assessments.findById(a.getId()).orElseThrow();

        assertThat(loaded.getOrganizationId()).isEqualTo(1L);
        assertThat(loaded.getWorkerId()).isEqualTo(101L);
        assertThat(loaded.getSessionId()).isEqualTo("S-101");
        assertThat(loaded.getStatus()).isEqualTo(AssessmentStatus.CLOSED);
        assertThat(loaded.getCloseReason()).isEqualTo(CloseReason.SESSION_ENDED);
        assertThat(loaded.getClosedAt()).isEqualTo(at(16, 0));
        assertThat(loaded.getCriticality()).isEqualTo(CriticalityLevel.HIGH);
        assertThat(loaded.getModelVersion()).isEqualTo(MODEL);
        assertThat(loaded.getLimitVersionId()).isEqualTo(7L);
        assertThat(loaded.getTriggerEventId()).isEqualTo(a.getTriggerEventId());
        assertThat(loaded.getAssessedAt()).isEqualTo(at(12, 0));
        assertThat(loaded.getProjection()).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(a.getProjection());
        assertThat(loaded.getRecommendation()).hasValueSatisfying(r -> {
            assertThat(r.type()).isEqualTo(RecommendationType.ROTATION);
            assertThat(r.targetWorkerId()).isEqualTo(200L);
            assertThat(r.targetPositionId()).isEqualTo(20L);
            assertThat(r.estimatedBenefitIndex()).isEqualByComparingTo("5760");
            assertThat(r.reason()).isEqualTo("Intercambiar con 200");
        });
        assertThat(loaded.getRedaction()).contains(red);
    }

    @Test
    void projectionWithoutExceedanceAndAssessmentWithoutRecommendation() {
        PreventiveAssessment a = PreventiveAssessment.project(UUID.randomUUID().toString(), 1L, 101L, "S-101",
                projection(null), MODEL, null, null, at(12, 0));
        a.assess(CriticalityLevel.LOW);
        assessments.save(a);

        PreventiveAssessment loaded = assessments.findById(a.getId()).orElseThrow();

        assertThat(loaded.getProjection().estimatedExceedanceAt()).isNull();
        assertThat(loaded.getRecommendation()).isEmpty();
        assertThat(loaded.getRedaction()).isEmpty();
        assertThat(loaded.getLimitVersionId()).isNull();
        assertThat(loaded.isActive()).isTrue();
    }

    @Test
    void supersedingThenCreatingInSameSessionKeepsOneActive() {
        PreventiveAssessment first = assessments.save(assessed(101L, "S-101", at(12, 0), CriticalityLevel.HIGH));
        first.supersede(at(12, 10));
        assessments.save(first);
        PreventiveAssessment second = assessments.save(assessed(101L, "S-101", at(12, 10), CriticalityLevel.HIGH));

        assertThat(assessments.findActiveBySessionId("S-101")).map(PreventiveAssessment::getId)
                .contains(second.getId());
        assertThat(assessments.findById(first.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void databaseRejectsTwoActiveAssessmentsForTheSameSession() {
        assessments.save(assessed(101L, "S-101", at(12, 0), CriticalityLevel.HIGH));

        assertThatThrownBy(() -> assessments.save(assessed(101L, "S-101", at(12, 5), CriticalityLevel.LOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void activeQueriesByWorkerAndOrganization() {
        PreventiveAssessment old = assessments.save(assessed(101L, "S-101", at(9, 0), CriticalityLevel.LOW));
        old.supersede(at(9, 30));
        assessments.save(old);
        PreventiveAssessment current = assessments.save(assessed(101L, "S-102", at(12, 0), CriticalityLevel.HIGH));
        assessments.save(assessed(300L, "S-300", at(12, 0), CriticalityLevel.CRITICAL));

        assertThat(assessments.findActiveByWorkerId(101L)).map(PreventiveAssessment::getId).contains(current.getId());
        assertThat(assessments.findActiveByWorkerId(999L)).isEmpty();
        assertThat(assessments.findActiveByOrganizationId(1L)).extracting(PreventiveAssessment::getWorkerId)
                .containsExactlyInAnyOrder(101L, 300L);
        assertThat(assessments.findActiveByOrganizationId(2L)).isEmpty();
    }

    @Test
    void searchFiltersSortsNewestFirstAndPaginates() {
        PreventiveAssessment closed = assessments.save(assessed(101L, "S-101", at(9, 0), CriticalityLevel.LOW));
        closed.supersede(at(9, 1));
        assessments.save(closed);
        assessments.save(assessed(101L, "S-101", at(10, 0), CriticalityLevel.LOW));
        assessments.save(assessed(200L, "S-200", at(11, 0), CriticalityLevel.LOW));

        PageResult<PreventiveAssessment> byWorker = assessments.search(
                new AssessmentSearchCriteria(1L, 101L, null, null, null), 0, 10);
        assertThat(byWorker.totalElements()).isEqualTo(2);
        assertThat(byWorker.content()).extracting(PreventiveAssessment::getAssessedAt)
                .containsExactly(at(10, 0), at(9, 0));

        PageResult<PreventiveAssessment> window = assessments.search(
                new AssessmentSearchCriteria(null, null, null, at(9, 30), at(11, 0)), 0, 10);
        assertThat(window.content()).extracting(PreventiveAssessment::getWorkerId).containsExactly(200L, 101L);

        PageResult<PreventiveAssessment> bySession = assessments.search(
                new AssessmentSearchCriteria(null, null, "S-200", null, null), 0, 10);
        assertThat(bySession.content()).hasSize(1);

        PageResult<PreventiveAssessment> page = assessments.search(
                new AssessmentSearchCriteria(1L, null, null, null, null), 1, 2);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.content()).extracting(PreventiveAssessment::getAssessedAt).containsExactly(at(9, 0));
    }

    // ─── Snapshots ───────────────────────────────────────────────────────────

    @Test
    void snapshotUpsertAndZoneQuery() {
        snapshots.save(new WorkerExposureSnapshot(1L, 101L, "S-101", "Z-1", 10L, dec("3600"), dec("12000"),
                at(8, 0), at(16, 0), at(10, 0), at(10, 0)));
        snapshots.save(new WorkerExposureSnapshot(1L, 200L, "S-200", "Z-1", 20L, dec("100"), dec("12000"),
                at(8, 0), null, at(10, 0), at(10, 0)));
        snapshots.save(new WorkerExposureSnapshot(1L, 300L, "S-300", "Z-2", 30L, dec("100"), dec("12000"),
                at(8, 0), null, at(10, 0), at(10, 0)));
        snapshots.save(new WorkerExposureSnapshot(1L, 101L, "S-101", "Z-1", 11L, dec("4000.5"), dec("12000"),
                at(8, 0), at(17, 0), at(11, 0), at(11, 0)));

        WorkerExposureSnapshot s = snapshots.findBySessionId("S-101").orElseThrow();
        assertThat(s.accumulatedIndex()).isEqualByComparingTo("4000.5");
        assertThat(s.positionId()).isEqualTo(11L);
        assertThat(s.plannedShiftEnd()).isEqualTo(at(17, 0));
        assertThat(s.capturedAt()).isEqualTo(at(11, 0));
        assertThat(snapshots.findBySessionId("S-200").orElseThrow().plannedShiftEnd()).isNull();
        assertThat(snapshots.findBySessionId("missing")).isEmpty();
        assertThat(snapshots.findByOrganizationIdAndZoneId(1L, "Z-1")).extracting(WorkerExposureSnapshot::workerId)
                .containsExactlyInAnyOrder(101L, 200L);
        assertThat(snapshots.findByOrganizationIdAndZoneId(2L, "Z-1")).isEmpty();
    }

    // ─── Outbox ──────────────────────────────────────────────────────────────

    private static OutboxEvent event(String id, String type, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", 1L);
        payload.put("workerId", 101L);
        payload.put("candidateWorkerIds", List.of(200L));
        payload.put("projectedExceedanceAt", null);
        payload.put("projectedIndex", dec("14400.000000"));
        payload.put("isProjection", true);
        payload.put("rationale", "Texto");
        return new OutboxEvent(id, type, 1, 1L, "a-1", occurredAt, "corr", "cause", payload, null);
    }

    @Test
    void outboxPendingOldestFirstThenInsertionOrder() {
        outbox.append(event("e-3", "RotationSuggested", at(12, 10)));
        outbox.append(event("e-1", "RotationSuggested", at(12, 0)));
        outbox.append(event("e-2", "PredictiveRiskDetected", at(12, 0)));

        PageResult<OutboxEvent> pending = outbox.findPending(0, 10);

        assertThat(pending.totalElements()).isEqualTo(3);
        assertThat(pending.content()).extracting(OutboxEvent::eventId).containsExactly("e-1", "e-2", "e-3");
        assertThat(outbox.findPending(1, 2).content()).extracting(OutboxEvent::eventId).containsExactly("e-3");
    }

    @Test
    void outboxPayloadAndEnvelopeRoundTrip() {
        outbox.append(event("e-1", "RotationSuggested", at(12, 0)));

        OutboxEvent e = outbox.findById("e-1").orElseThrow();

        assertThat(e.eventType()).isEqualTo("RotationSuggested");
        assertThat(e.schemaVersion()).isEqualTo(1);
        assertThat(e.organizationId()).isEqualTo(1L);
        assertThat(e.aggregateId()).isEqualTo("a-1");
        assertThat(e.occurredAt()).isEqualTo(at(12, 0));
        assertThat(e.correlationId()).isEqualTo("corr");
        assertThat(e.causationId()).isEqualTo("cause");
        assertThat(e.payload())
                .containsEntry("organizationId", 1L)
                .containsEntry("workerId", 101L)
                .containsEntry("candidateWorkerIds", List.of(200L))
                .containsEntry("projectedExceedanceAt", null)
                .containsEntry("isProjection", true)
                .containsEntry("rationale", "Texto");
        assertThat((BigDecimal) e.payload().get("projectedIndex")).isEqualByComparingTo("14400");
        assertThat(e.payload().keySet()).containsExactly("organizationId", "workerId", "candidateWorkerIds",
                "projectedExceedanceAt", "projectedIndex", "isProjection", "rationale");
    }

    @Test
    void ackIsIdempotentAndUnknownIsEmpty() {
        outbox.append(event("e-1", "RotationSuggested", at(12, 0)));

        OutboxEvent first = outbox.acknowledge("e-1", at(12, 5)).orElseThrow();
        OutboxEvent second = outbox.acknowledge("e-1", at(12, 9)).orElseThrow();

        assertThat(first.publishedAt()).isEqualTo(at(12, 5));
        assertThat(second.publishedAt()).isEqualTo(at(12, 5));
        assertThat(outbox.findPending(0, 10).content()).isEmpty();
        assertThat(outbox.acknowledge("missing", at(12, 5))).isEmpty();
    }

    @Test
    void outboxRejectsDuplicateEventId() {
        outbox.append(event("e-1", "RotationSuggested", at(12, 0)));

        assertThatThrownBy(() -> outbox.append(event("e-1", "RotationSuggested", at(12, 0))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─── Processed messages ──────────────────────────────────────────────────

    @Test
    void processedMessages() {
        assertThat(processed.isProcessed("w-1")).isFalse();

        processed.markProcessed("w-1", at(12, 0));

        assertThat(processed.isProcessed("w-1")).isTrue();
        assertThat(processed.isProcessed("w-2")).isFalse();
    }

    @Test
    void processedMessageCannotBeRegisteredTwice() {
        processed.markProcessed("w-1", at(12, 0));

        assertThatThrownBy(() -> processed.markProcessed("w-1", at(12, 5)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
