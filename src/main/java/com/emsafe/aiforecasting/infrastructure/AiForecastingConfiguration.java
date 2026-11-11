package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.AckOutboxEventHandler;
import com.emsafe.aiforecasting.application.AssessmentQueryHandler;
import com.emsafe.aiforecasting.application.CriticalWorkersQueryHandler;
import com.emsafe.aiforecasting.application.ForecastingAccessPolicy;
import com.emsafe.aiforecasting.application.ForecastingActorDirectory;
import com.emsafe.aiforecasting.application.ForecastingEventFactory;
import com.emsafe.aiforecasting.application.ModelVersionProvider;
import com.emsafe.aiforecasting.application.OutboxQueryHandler;
import com.emsafe.aiforecasting.application.PreventiveAssessmentWorkflow;
import com.emsafe.aiforecasting.application.ShiftUpdatedHandler;
import com.emsafe.aiforecasting.application.TriggerAssessmentHandler;
import com.emsafe.aiforecasting.application.UpsertExposureSnapshotHandler;
import com.emsafe.aiforecasting.domain.model.ForecastingThresholds;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.ForecastingOutboxPort;
import com.emsafe.aiforecasting.domain.ports.NaturalLanguagePort;
import com.emsafe.aiforecasting.domain.ports.ProcessedMessagePort;
import com.emsafe.aiforecasting.domain.ports.WorkerSnapshotRepository;
import com.emsafe.aiforecasting.domain.services.CriticalityAssessmentService;
import com.emsafe.aiforecasting.domain.services.ExposureProjectionService;
import com.emsafe.aiforecasting.domain.services.FallbackMessagePolicy;
import com.emsafe.aiforecasting.domain.services.RotationCandidateSelector;
import com.emsafe.aiforecasting.domain.services.RotationRecommendationService;
import com.emsafe.aiforecasting.infrastructure.llm.FallbackOnlyRedactionAdapter;
import com.emsafe.aiforecasting.infrastructure.llm.GeminiRedactionAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Cableado del contexto AI Forecasting. El dominio y los handlers son clases planas; aquí se
 * registran como beans (los handlers quedan envueltos en proxies transaccionales).
 * El reloj lleva calificador propio para no chocar con un {@code Clock} de otro contexto.
 */
@Configuration
@EnableConfigurationProperties(ForecastingProperties.class)
public class AiForecastingConfiguration {

    public static final String CLOCK = "aiForecastingClock";
    private static final Logger log = LoggerFactory.getLogger(AiForecastingConfiguration.class);

    /**
     * Reloj UTC con resolución de microsegundos, la misma de DATETIME(6): así un instante
     * devuelto antes de persistir es idéntico al que se lee después (p. ej. el ack del outbox).
     */
    @Bean(CLOCK)
    Clock aiForecastingClock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    // ─── Dominio ─────────────────────────────────────────────────────────────

    @Bean
    ForecastingThresholds forecastingThresholds(ForecastingProperties properties) {
        return properties.thresholds();
    }

    @Bean
    ExposureProjectionService exposureProjectionService() {
        return new ExposureProjectionService();
    }

    @Bean
    RotationCandidateSelector rotationCandidateSelector(ExposureProjectionService projections,
                                                        ForecastingThresholds thresholds) {
        return new RotationCandidateSelector(projections, thresholds);
    }

    @Bean
    CriticalityAssessmentService criticalityAssessmentService(ForecastingThresholds thresholds) {
        return new CriticalityAssessmentService(thresholds);
    }

    @Bean
    RotationRecommendationService rotationRecommendationService() {
        return new RotationRecommendationService();
    }

    @Bean
    FallbackMessagePolicy fallbackMessagePolicy() {
        return new FallbackMessagePolicy();
    }

    // ─── Adaptadores ─────────────────────────────────────────────────────────

    @Bean
    ModelVersionProvider modelVersionProvider(ForecastingProperties properties) {
        return new PropertiesModelVersionProvider(properties);
    }

    @Bean
    NaturalLanguagePort naturalLanguagePort(ForecastingProperties properties, FallbackMessagePolicy fallback,
                                            @Qualifier(CLOCK) Clock clock,
                                            @Value("${gemini.api-key:}") String apiKey,
                                            @Value("${gemini.model:gemini-2.5-flash}") String model) {
        if (!properties.getLlm().isEnabled()) {
            log.info("AI Forecasting: LLM disabled, using template redaction");
            return new FallbackOnlyRedactionAdapter(fallback, clock);
        }
        log.info("AI Forecasting: Gemini redaction enabled (model {}, timeout {} ms)",
                model, properties.getLlm().getTimeoutMs());
        return new GeminiRedactionAdapter(
                GeminiRedactionAdapter.restTemplateWithTimeout(Duration.ofMillis(properties.getLlm().getTimeoutMs())),
                apiKey, model, fallback, clock);
    }

    // ─── Aplicación ──────────────────────────────────────────────────────────

    @Bean
    ForecastingEventFactory forecastingEventFactory() {
        return new ForecastingEventFactory();
    }

    @Bean
    PreventiveAssessmentWorkflow preventiveAssessmentWorkflow(
            ExposureProjectionService projections, RotationCandidateSelector selector,
            CriticalityAssessmentService criticality, RotationRecommendationService recommendations,
            FallbackMessagePolicy fallback, NaturalLanguagePort naturalLanguage, AssessmentRepository assessments,
            WorkerSnapshotRepository snapshots, ForecastingOutboxPort outbox, ForecastingEventFactory events,
            ModelVersionProvider modelVersions, ForecastingProperties properties, @Qualifier(CLOCK) Clock clock) {
        return new PreventiveAssessmentWorkflow(projections, selector, criticality, recommendations, fallback,
                naturalLanguage, assessments, snapshots, outbox, events, modelVersions,
                properties.defaultShift(), clock);
    }

    @Bean
    UpsertExposureSnapshotHandler upsertExposureSnapshotHandler(WorkerSnapshotRepository snapshots,
                                                                ProcessedMessagePort processed,
                                                                @Qualifier(CLOCK) Clock clock) {
        return new UpsertExposureSnapshotHandler(snapshots, processed, clock);
    }

    @Bean
    TriggerAssessmentHandler triggerAssessmentHandler(WorkerSnapshotRepository snapshots,
                                                      ProcessedMessagePort processed,
                                                      PreventiveAssessmentWorkflow workflow,
                                                      @Qualifier(CLOCK) Clock clock) {
        return new TriggerAssessmentHandler(snapshots, processed, workflow, clock);
    }

    @Bean
    ShiftUpdatedHandler shiftUpdatedHandler(WorkerSnapshotRepository snapshots, AssessmentRepository assessments,
                                            ProcessedMessagePort processed, PreventiveAssessmentWorkflow workflow,
                                            @Qualifier(CLOCK) Clock clock) {
        return new ShiftUpdatedHandler(snapshots, assessments, processed, workflow, clock);
    }

    @Bean
    AssessmentQueryHandler assessmentQueryHandler(AssessmentRepository assessments) {
        return new AssessmentQueryHandler(assessments);
    }

    @Bean
    CriticalWorkersQueryHandler criticalWorkersQueryHandler(AssessmentRepository assessments) {
        return new CriticalWorkersQueryHandler(assessments);
    }

    @Bean
    OutboxQueryHandler outboxQueryHandler(ForecastingOutboxPort outbox) {
        return new OutboxQueryHandler(outbox);
    }

    @Bean
    ForecastingAccessPolicy forecastingAccessPolicy(ForecastingActorDirectory directory) {
        return new ForecastingAccessPolicy(directory);
    }

    @Bean
    AckOutboxEventHandler ackOutboxEventHandler(ForecastingOutboxPort outbox, @Qualifier(CLOCK) Clock clock) {
        return new AckOutboxEventHandler(outbox, clock);
    }
}
