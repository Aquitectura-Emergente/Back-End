package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.application.InboxOutcome;
import com.emsafe.aiforecasting.application.commands.ExposureWarningRaisedCommand;
import com.emsafe.aiforecasting.application.commands.ShiftUpdatedCommand;
import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import com.emsafe.aiforecasting.domain.model.ExposureMethod;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.aiforecasting.domain.ports.PageResult;
import com.emsafe.aiforecasting.interfaces.rest.dto.ForecastingRequests;
import com.emsafe.aiforecasting.interfaces.rest.dto.ForecastingResources;
import com.emsafe.shared.dto.PageResponse;

import java.util.function.Function;

/** Traduce entre DTOs REST y comandos/modelos de aplicación. Sin lógica de negocio. */
final class ForecastingAssembler {

    private ForecastingAssembler() {
    }

    // ─── Requests → comandos ─────────────────────────────────────────────────

    static UpsertExposureSnapshotCommand toCommand(ForecastingRequests.ExposureSnapshot r) {
        return new UpsertExposureSnapshotCommand(r.eventId(), r.correlationId(), r.organizationId(), r.workerId(),
                r.sessionId(), r.zoneId(), r.positionId(), r.accumulatedIndex(), r.limitIndex(),
                r.sessionStartedAt(), r.plannedShiftEnd(), r.capturedAt());
    }

    static ExposureWarningRaisedCommand toCommand(ForecastingRequests.ExposureWarningRaised r) {
        return new ExposureWarningRaisedCommand(r.eventId(), r.correlationId(), r.organizationId(), r.sessionId(),
                r.workerId(), r.accumulatedIndex(), r.limitIndex(), r.limitVersionId(), r.capturedAt(),
                r.methodId(), r.unit());
    }

    static ShiftUpdatedCommand toCommand(ForecastingRequests.ShiftUpdated r) {
        return new ShiftUpdatedCommand(r.eventId(), r.correlationId(), r.organizationId(), r.workerId(),
                r.sessionId(), r.zoneId(), r.positionId(), r.plannedShiftEnd());
    }

    // ─── Modelos → respuestas ────────────────────────────────────────────────

    static ForecastingResources.InboxResult toResource(InboxOutcome o) {
        return new ForecastingResources.InboxResult(o.eventId(), o.duplicate(), o.assessmentId());
    }

    static ForecastingResources.Assessment toResource(PreventiveAssessment a) {
        ExposureProjection p = a.getProjection();
        return new ForecastingResources.Assessment(
                a.getId(), a.getOrganizationId(), a.getWorkerId(), a.getSessionId(),
                a.getStatus().name(),
                a.getCriticality() == null ? null : a.getCriticality().name(),
                new ForecastingResources.Projection(p.currentIndex(), p.limitIndex(), p.accumulationRate(),
                        p.projectedIndexAtShiftEnd(), p.remainingMargin(), p.consumedRatio(), p.projectedRatio(),
                        p.estimatedExceedanceAt(), p.shiftEnd(), ExposureMethod.MICROTESLA_SECONDS),
                a.getRecommendation().map(r -> new ForecastingResources.Recommendation(r.type().name(),
                        r.sourceWorkerId(), r.targetWorkerId(), r.sourcePositionId(), r.targetPositionId(),
                        r.estimatedBenefitIndex(), r.reason())).orElse(null),
                a.getRedaction().map(n -> new ForecastingResources.Redaction(n.text(), n.generatedBy().name(),
                        n.generatedAt())).orElse(null),
                new ForecastingResources.ModelVersion(a.getModelVersion().modelId(), a.getModelVersion().version(),
                        a.getModelVersion().code()),
                a.getLimitVersionId(), a.getTriggerEventId(), a.getAssessedAt(), a.getClosedAt(),
                a.getCloseReason() == null ? null : a.getCloseReason().name(),
                true);
    }

    static ForecastingResources.OutboxEvent toResource(OutboxEvent e) {
        return new ForecastingResources.OutboxEvent(e.eventId(), e.eventType(), e.schemaVersion(),
                e.organizationId(), e.aggregateId(), e.occurredAt(), e.correlationId(), e.causationId(),
                e.payload(), e.publishedAt());
    }

    static <T, R> PageResponse<R> toPage(PageResult<T> page, Function<T, R> mapper) {
        int totalPages = page.size() == 0 ? 0 : (int) Math.ceil((double) page.totalElements() / page.size());
        return new PageResponse<>(page.content().stream().map(mapper).toList(), page.page(), page.size(),
                page.totalElements(), totalPages, page.page() >= totalPages - 1);
    }
}
