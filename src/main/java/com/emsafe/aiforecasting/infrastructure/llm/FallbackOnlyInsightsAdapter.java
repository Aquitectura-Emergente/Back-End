package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.WorkerNames;
import com.emsafe.aiforecasting.domain.ports.PeriodInsightsPort;
import com.emsafe.aiforecasting.domain.services.InsightsFallbackPolicy;

import java.time.Clock;

/** Adaptador usado con el LLM deshabilitado o sin clave: solo reglas deterministas, sin red. */
public class FallbackOnlyInsightsAdapter implements PeriodInsightsPort {

    private final InsightsFallbackPolicy fallback;
    private final Clock clock;

    public FallbackOnlyInsightsAdapter(InsightsFallbackPolicy fallback, Clock clock) {
        this.fallback = fallback;
        this.clock = clock;
    }

    @Override
    public PeriodInsights analyze(AnalyticsSummary summary, WorkerNames names) {
        return fallback.analyze(summary, names, clock.instant());
    }
}
