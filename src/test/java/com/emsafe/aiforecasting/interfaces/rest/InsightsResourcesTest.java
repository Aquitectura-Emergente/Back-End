package com.emsafe.aiforecasting.interfaces.rest;

import com.emsafe.aiforecasting.domain.InsightsFixtures;
import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.services.InsightsFallbackPolicy;
import com.emsafe.aiforecasting.interfaces.rest.dto.InsightsResources.Insights;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** El disclaimer depende del origen: solo dice "IA" cuando el análisis lo generó Gemini. */
class InsightsResourcesTest {

    private static final Instant NOW = Instant.parse("2026-11-22T15:00:00Z");

    private static PeriodInsights withSource(RedactionSource source, String model) {
        PeriodInsights f = new InsightsFallbackPolicy().analyze(InsightsFixtures.rich(), NOW);
        return new PeriodInsights(f.organizationId(), f.from(), f.to(), f.zone(), source, model, f.generatedAt(),
                f.summary(), f.findings(), f.recommendations(), f.dataQuality());
    }

    @Test
    void llmDisclaimerNamesGemini() {
        Insights dto = Insights.from(withSource(RedactionSource.LLM, "gemini-2.5-flash"));

        assertThat(dto.generatedBy()).isEqualTo("LLM");
        assertThat(dto.model()).isEqualTo("gemini-2.5-flash");
        assertThat(dto.isAdvisory()).isTrue();
        assertThat(dto.disclaimer()).isEqualTo("Análisis generado por IA (Gemini). Las cifras provienen del motor "
                + "de pronóstico y deben ser validadas por el SSO.");
    }

    @Test
    void fallbackDisclaimerDoesNotClaimAi() {
        Insights dto = Insights.from(withSource(RedactionSource.FALLBACK, null));

        assertThat(dto.generatedBy()).isEqualTo("FALLBACK");
        assertThat(dto.model()).isNull();
        assertThat(dto.isAdvisory()).isTrue();
        assertThat(dto.disclaimer()).isEqualTo("Análisis generado con reglas automáticas (IA no disponible). Las "
                + "cifras provienen del motor de pronóstico y deben ser validadas por el SSO.");
    }
}
