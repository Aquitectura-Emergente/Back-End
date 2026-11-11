package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.ports.ProcessedMessagePort;
import com.emsafe.aiforecasting.domain.ports.WorkerSnapshotRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/** Inbox {@code exposure-snapshot}: alta o actualización del modelo de lectura local. */
public class UpsertExposureSnapshotHandler {

    private final WorkerSnapshotRepository snapshots;
    private final ProcessedMessagePort processed;
    private final Clock clock;

    public UpsertExposureSnapshotHandler(WorkerSnapshotRepository snapshots, ProcessedMessagePort processed,
                                         Clock clock) {
        this.snapshots = snapshots;
        this.processed = processed;
        this.clock = clock;
    }

    @Transactional
    public InboxOutcome handle(UpsertExposureSnapshotCommand cmd) {
        requireText(cmd.eventId(), "eventId");
        if (processed.isProcessed(cmd.eventId())) {
            return InboxOutcome.duplicateOf(cmd.eventId());
        }
        Instant now = clock.instant();
        WorkerExposureSnapshot incoming = new WorkerExposureSnapshot(cmd.organizationId(), cmd.workerId(),
                cmd.sessionId(), cmd.zoneId(), cmd.positionId(), cmd.accumulatedIndex(), cmd.limitIndex(),
                cmd.sessionStartedAt(), cmd.plannedShiftEnd(), cmd.capturedAt(), now);

        WorkerExposureSnapshot result = snapshots.findBySessionId(cmd.sessionId())
                .map(existing -> existing.merge(incoming))
                .orElse(incoming);
        snapshots.save(result);
        processed.markProcessed(cmd.eventId(), now);
        return InboxOutcome.processed(cmd.eventId(), null);
    }
}
