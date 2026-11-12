package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.NaturalLanguagePort;
import com.emsafe.aiforecasting.domain.services.FallbackMessagePolicy;

import java.time.Clock;

/** Adaptador usado con {@code emsafe.ai-forecasting.llm.enabled=false}: solo plantilla, sin red. */
public class FallbackOnlyRedactionAdapter implements NaturalLanguagePort {

    private final FallbackMessagePolicy fallback;
    private final Clock clock;

    public FallbackOnlyRedactionAdapter(FallbackMessagePolicy fallback, Clock clock) {
        this.fallback = fallback;
        this.clock = clock;
    }

    @Override
    public NaturalLanguageRedaction redact(PreventiveAssessment assessment) {
        return fallback.redact(assessment, clock.instant());
    }
}
