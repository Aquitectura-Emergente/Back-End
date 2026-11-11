package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.commands.ShiftUpdatedCommand;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.ProcessedMessagePort;
import com.emsafe.aiforecasting.domain.ports.WorkerSnapshotRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/**
 * Inbox {@code shift-updated}: cambia zona/puesto/fin de turno y, si la sesión tiene una
 * evaluación activa, la recalcula (supersede + nueva) conservando su limitVersionId.
 */
public class ShiftUpdatedHandler {

    private final WorkerSnapshotRepository snapshots;
    private final AssessmentRepository assessments;
    private final ProcessedMessagePort processed;
    private final PreventiveAssessmentWorkflow workflow;
    private final Clock clock;

    public ShiftUpdatedHandler(WorkerSnapshotRepository snapshots, AssessmentRepository assessments,
                               ProcessedMessagePort processed, PreventiveAssessmentWorkflow workflow, Clock clock) {
        this.snapshots = snapshots;
        this.assessments = assessments;
        this.processed = processed;
        this.workflow = workflow;
        this.clock = clock;
    }

    @Transactional
    public InboxOutcome handle(ShiftUpdatedCommand cmd) {
        requireText(cmd.eventId(), "eventId");
        if (processed.isProcessed(cmd.eventId())) {
            return InboxOutcome.duplicateOf(cmd.eventId());
        }
        requireText(cmd.sessionId(), "sessionId");
        Instant now = clock.instant();

        WorkerExposureSnapshot snapshot = snapshots.findBySessionId(cmd.sessionId())
                .orElseThrow(() -> ForecastingRuleException.conflict(
                        "no exposure snapshot for session " + cmd.sessionId() + "; send exposure-snapshot first"));
        snapshot.ensureBelongsTo(cmd.organizationId(), cmd.workerId());
        WorkerExposureSnapshot moved = snapshots.save(
                snapshot.withShift(cmd.zoneId(), cmd.positionId(), cmd.plannedShiftEnd(), now));

        EventCause cause = new EventCause(cmd.eventId(), cmd.correlationId());
        String assessmentId = assessments.findActiveBySessionId(cmd.sessionId())
                .map(active -> workflow.evaluate(moved, active.getLimitVersionId(), cause).getId())
                .orElse(null);
        processed.markProcessed(cmd.eventId(), now);
        return InboxOutcome.processed(cmd.eventId(), assessmentId);
    }
}
