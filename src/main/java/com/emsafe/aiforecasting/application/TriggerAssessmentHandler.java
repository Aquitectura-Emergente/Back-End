package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.commands.ExposureWarningRaisedCommand;
import com.emsafe.aiforecasting.domain.model.ExposureMethod;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.ports.ProcessedMessagePort;
import com.emsafe.aiforecasting.domain.ports.WorkerSnapshotRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/**
 * Inbox {@code exposure-warning-raised}: actualiza el snapshot con la lectura del aviso y
 * ejecuta la evaluación preventiva completa en una sola transacción.
 */
public class TriggerAssessmentHandler {

    private final WorkerSnapshotRepository snapshots;
    private final ProcessedMessagePort processed;
    private final PreventiveAssessmentWorkflow workflow;
    private final Clock clock;

    public TriggerAssessmentHandler(WorkerSnapshotRepository snapshots, ProcessedMessagePort processed,
                                    PreventiveAssessmentWorkflow workflow, Clock clock) {
        this.snapshots = snapshots;
        this.processed = processed;
        this.workflow = workflow;
        this.clock = clock;
    }

    @Transactional
    public InboxOutcome handle(ExposureWarningRaisedCommand cmd) {
        requireText(cmd.eventId(), "eventId");
        if (processed.isProcessed(cmd.eventId())) {
            return InboxOutcome.duplicateOf(cmd.eventId());
        }
        ExposureMethod.ensureSupported(cmd.methodId(), cmd.unit());
        requireText(cmd.sessionId(), "sessionId");
        Instant now = clock.instant();

        WorkerExposureSnapshot snapshot = snapshots.findBySessionId(cmd.sessionId())
                .orElseThrow(() -> ForecastingRuleException.conflict(
                        "no exposure snapshot for session " + cmd.sessionId() + "; send exposure-snapshot first"));
        snapshot.ensureBelongsTo(cmd.organizationId(), cmd.workerId());
        WorkerExposureSnapshot updated = snapshots.save(
                snapshot.withCapture(cmd.accumulatedIndex(), cmd.limitIndex(), cmd.capturedAt(), now));

        PreventiveAssessment assessment = workflow.evaluate(updated, cmd.limitVersionId(),
                new EventCause(cmd.eventId(), cmd.correlationId()));
        processed.markProcessed(cmd.eventId(), now);
        return InboxOutcome.processed(cmd.eventId(), assessment.getId());
    }
}
