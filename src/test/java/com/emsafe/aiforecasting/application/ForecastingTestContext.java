package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.commands.ExposureWarningRaisedCommand;
import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import com.emsafe.aiforecasting.application.fakes.InMemoryAssessmentRepository;
import com.emsafe.aiforecasting.application.fakes.InMemoryOutbox;
import com.emsafe.aiforecasting.application.fakes.InMemoryProcessedMessages;
import com.emsafe.aiforecasting.application.fakes.InMemoryWorkerSnapshotRepository;
import com.emsafe.aiforecasting.application.fakes.MutableClock;
import com.emsafe.aiforecasting.application.fakes.StubNaturalLanguagePort;
import com.emsafe.aiforecasting.domain.model.ForecastingThresholds;
import com.emsafe.aiforecasting.domain.model.ModelVersion;
import com.emsafe.aiforecasting.domain.services.CriticalityAssessmentService;
import com.emsafe.aiforecasting.domain.services.ExposureProjectionService;
import com.emsafe.aiforecasting.domain.services.FallbackMessagePolicy;
import com.emsafe.aiforecasting.domain.services.RotationCandidateSelector;
import com.emsafe.aiforecasting.domain.services.RotationRecommendationService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Cablea todos los handlers sobre adaptadores en memoria.
 *
 * <p>Escenario: organización 1, zona Z-1, turno 08:00–16:00 UTC, reloj a las 12:00.
 * Límite 12000 uT·s para todos. A las 12:00 quedan 14400 s de turno.</p>
 */
public class ForecastingTestContext {

    public static final Long ORG = 1L;
    public static final String ZONE = "Z-1";
    public static final Instant DAY = Instant.parse("2026-11-10T00:00:00Z");
    public static final String LIMIT = "12000";

    public final MutableClock clock = new MutableClock(at(12, 0));
    public final InMemoryAssessmentRepository assessments = new InMemoryAssessmentRepository();
    public final InMemoryWorkerSnapshotRepository snapshots = new InMemoryWorkerSnapshotRepository();
    public final InMemoryOutbox outbox = new InMemoryOutbox();
    public final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
    public final StubNaturalLanguagePort llm = new StubNaturalLanguagePort(clock);

    public final UpsertExposureSnapshotHandler upsertSnapshot;
    public final TriggerAssessmentHandler trigger;
    public final ShiftUpdatedHandler shiftUpdated;
    public final AssessmentQueryHandler assessmentQueries;
    public final CriticalWorkersQueryHandler criticalWorkers;
    public final OutboxQueryHandler outboxQueries;
    public final AckOutboxEventHandler ack;

    public ForecastingTestContext() {
        ForecastingThresholds thresholds = new ForecastingThresholds(
                new BigDecimal("0.10"), new BigDecimal("0.90"), new BigDecimal("0.90"));
        ExposureProjectionService projections = new ExposureProjectionService();
        PreventiveAssessmentWorkflow workflow = new PreventiveAssessmentWorkflow(
                projections,
                new RotationCandidateSelector(projections, thresholds),
                new CriticalityAssessmentService(thresholds),
                new RotationRecommendationService(),
                new FallbackMessagePolicy(),
                llm, assessments, snapshots, outbox,
                new ForecastingEventFactory(),
                () -> new ModelVersion("LINEAR_RATE", "1"),
                Duration.ofHours(8), clock);

        upsertSnapshot = new UpsertExposureSnapshotHandler(snapshots, processed, clock);
        trigger = new TriggerAssessmentHandler(snapshots, processed, workflow, clock);
        shiftUpdated = new ShiftUpdatedHandler(snapshots, assessments, processed, workflow, clock);
        assessmentQueries = new AssessmentQueryHandler(assessments);
        criticalWorkers = new CriticalWorkersQueryHandler(assessments);
        outboxQueries = new OutboxQueryHandler(outbox);
        ack = new AckOutboxEventHandler(outbox, clock);
    }

    public static Instant at(int hour, int minute) {
        return DAY.plusSeconds(hour * 3600L + minute * 60L);
    }

    public static String session(long workerId) {
        return "S-" + workerId;
    }

    /** Snapshot capturado a las 12:00, turno 08:00–16:00. */
    public static UpsertExposureSnapshotCommand snapshotCmd(String eventId, long workerId, Long positionId,
                                                            String index) {
        return new UpsertExposureSnapshotCommand(eventId, null, ORG, workerId, session(workerId), ZONE,
                positionId, new BigDecimal(index), new BigDecimal(LIMIT), at(8, 0), at(16, 0), at(12, 0));
    }

    public static ExposureWarningRaisedCommand warningCmd(String eventId, long workerId, String index) {
        return new ExposureWarningRaisedCommand(eventId, "corr-" + eventId, ORG, session(workerId), workerId,
                new BigDecimal(index), new BigDecimal(LIMIT), 7L, at(12, 0),
                ExposureWarningRaisedCommand.SUPPORTED_METHOD, ExposureWarningRaisedCommand.SUPPORTED_UNIT);
    }

    /** Fuente 101 (puesto 10) + candidato 200 (puesto 20, tasa 0.1) en la zona. */
    public void seedHighScenario() {
        upsertSnapshot.handle(snapshotCmd("snap-101", 101L, 10L, "6000"));
        upsertSnapshot.handle(snapshotCmd("snap-200", 200L, 20L, "1440"));
    }
}
