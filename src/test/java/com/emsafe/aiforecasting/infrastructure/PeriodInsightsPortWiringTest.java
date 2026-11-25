package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.domain.ports.PeriodInsightsPort;
import com.emsafe.aiforecasting.domain.services.InsightsFallbackPolicy;
import com.emsafe.aiforecasting.infrastructure.llm.FallbackOnlyInsightsAdapter;
import com.emsafe.aiforecasting.infrastructure.llm.GeminiInsightsAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué adaptador del "Análisis con IA" se usa según llm.enabled y la clave. */
@ExtendWith(OutputCaptureExtension.class)
class PeriodInsightsPortWiringTest {

    private PeriodInsightsPort wire(boolean enabled, String apiKey) {
        ForecastingProperties properties = new ForecastingProperties();
        properties.getLlm().setEnabled(enabled);
        return new AiForecastingConfiguration().periodInsightsPort(properties, new InsightsFallbackPolicy(),
                Clock.systemUTC(), apiKey, "gemini-2.5-flash");
    }

    @Test
    void disabledOrWithoutKeyUsesTheDeterministicRules() {
        assertThat(wire(false, "test-key-not-real")).isInstanceOf(FallbackOnlyInsightsAdapter.class);
        assertThat(wire(true, "")).isInstanceOf(FallbackOnlyInsightsAdapter.class);
        assertThat(wire(true, "  ")).isInstanceOf(FallbackOnlyInsightsAdapter.class);
        assertThat(wire(true, null)).isInstanceOf(FallbackOnlyInsightsAdapter.class);
    }

    @Test
    void enabledWithKeyUsesGeminiAndNeverLogsTheKey(CapturedOutput output) {
        String fakeKey = "test-key-not-real-0123456789";

        assertThat(wire(true, fakeKey)).isInstanceOf(GeminiInsightsAdapter.class);
        assertThat(output).contains("Gemini period insights enabled").doesNotContain(fakeKey);
    }
}
