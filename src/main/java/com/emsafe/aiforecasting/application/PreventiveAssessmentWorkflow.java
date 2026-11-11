package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RotationCandidate;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.ForecastingOutboxPort;
import com.emsafe.aiforecasting.domain.ports.NaturalLanguagePort;
import com.emsafe.aiforecasting.domain.ports.WorkerSnapshotRepository;
import com.emsafe.aiforecasting.domain.services.CriticalityAssessmentService;
import com.emsafe.aiforecasting.domain.services.ExposureProjectionService;
import com.emsafe.aiforecasting.domain.services.FallbackMessagePolicy;
import com.emsafe.aiforecasting.domain.services.RotationCandidateSelector;
import com.emsafe.aiforecasting.domain.services.RotationRecommendationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orquesta una evaluación completa: proyección → criticidad → recomendación → redacción → outbox.
 * No contiene reglas propias: todas viven en los servicios de dominio y en el agregado.
 * Se invoca dentro de la transacción del handler, así que el cambio de estado y el outbox
 * se confirman juntos.
 */
public class PreventiveAssessmentWorkflow {

    private static final Logger log = LoggerFactory.getLogger(PreventiveAssessmentWorkflow.class);

    private final ExposureProjectionService projections;
    private final RotationCandidateSelector candidateSelector;
    private final CriticalityAssessmentService criticalityService;
    private final RotationRecommendationService recommendationService;
    private final FallbackMessagePolicy fallback;
    private final NaturalLanguagePort naturalLanguage;
    private final AssessmentRepository assessments;
    private final WorkerSnapshotRepository snapshots;
    private final ForecastingOutboxPort outbox;
    private final ForecastingEventFactory events;
    private final ModelVersionProvider modelVersions;
    private final Duration defaultShift;
    private final Clock clock;

    public PreventiveAssessmentWorkflow(ExposureProjectionService projections,
                                        RotationCandidateSelector candidateSelector,
                                        CriticalityAssessmentService criticalityService,
                                        RotationRecommendationService recommendationService,
                                        FallbackMessagePolicy fallback,
                                        NaturalLanguagePort naturalLanguage,
                                        AssessmentRepository assessments,
                                        WorkerSnapshotRepository snapshots,
                                        ForecastingOutboxPort outbox,
                                        ForecastingEventFactory events,
                                        ModelVersionProvider modelVersions,
                                        Duration defaultShift,
                                        Clock clock) {
        this.projections = projections;
        this.candidateSelector = candidateSelector;
        this.criticalityService = criticalityService;
        this.recommendationService = recommendationService;
        this.fallback = fallback;
        this.naturalLanguage = naturalLanguage;
        this.assessments = assessments;
        this.snapshots = snapshots;
        this.outbox = outbox;
        this.events = events;
        this.modelVersions = modelVersions;
        this.defaultShift = defaultShift;
        this.clock = clock;
    }

    /** Evalúa la sesión del snapshot. Si ya había una evaluación activa, la supersede. */
    public PreventiveAssessment evaluate(WorkerExposureSnapshot snapshot, Long limitVersionId, EventCause cause) {
        Instant now = clock.instant();
        // Se proyecta antes de tocar nada: si la proyección es inválida (422) no hay efectos.
        ExposureProjection projection = projections.project(snapshot, defaultShift);

        assessments.findActiveBySessionId(snapshot.sessionId()).ifPresent(previous -> {
            previous.supersede(now);
            assessments.save(previous);
        });

        Optional<RotationCandidate> candidate =
                candidateSelector.selectBest(snapshot, projection, candidatePool(snapshot));
        CriticalityLevel criticality = criticalityService.assess(projection, candidate.isPresent());

        PreventiveAssessment assessment = PreventiveAssessment.project(UUID.randomUUID().toString(),
                snapshot.organizationId(), snapshot.workerId(), snapshot.sessionId(), projection,
                modelVersions.current(), limitVersionId, cause.eventId(), now);
        assessment.assess(criticality);
        recommendationService.recommend(criticality, snapshot, projection, candidate)
                .ifPresent(assessment::recommend);
        assessment.attachRedaction(redact(assessment, now));

        assessments.save(assessment);
        events.eventsFor(assessment, cause, now).forEach(outbox::append);
        return assessment;
    }

    private List<WorkerExposureSnapshot> candidatePool(WorkerExposureSnapshot snapshot) {
        if (snapshot.zoneId() == null) {
            return List.of();
        }
        return snapshots.findByOrganizationIdAndZoneId(snapshot.organizationId(), snapshot.zoneId());
    }

    /** La evaluación nunca falla por el LLM: ante error o respuesta vacía se usa la plantilla. */
    private NaturalLanguageRedaction redact(PreventiveAssessment assessment, Instant now) {
        try {
            NaturalLanguageRedaction redaction = naturalLanguage.redact(assessment);
            if (redaction != null) {
                return redaction;
            }
            log.warn("LLM returned no redaction for assessment {}; using fallback", assessment.getId());
        } catch (RuntimeException e) {
            log.warn("LLM redaction failed for assessment {}; using fallback: {}", assessment.getId(), e.toString());
        }
        return fallback.redact(assessment, now);
    }
}
