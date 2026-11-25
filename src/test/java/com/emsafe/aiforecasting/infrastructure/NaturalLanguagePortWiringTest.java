package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.domain.ports.NaturalLanguagePort;
import com.emsafe.aiforecasting.domain.services.FallbackMessagePolicy;
import com.emsafe.aiforecasting.infrastructure.llm.FallbackOnlyRedactionAdapter;
import com.emsafe.aiforecasting.infrastructure.llm.GeminiRedactionAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** Qué adaptador de redacción se usa según llm.enabled y la clave, y qué se avisa al arrancar. */
@ExtendWith(OutputCaptureExtension.class)
class NaturalLanguagePortWiringTest {

    private static final String MISSING_KEY_WARNING = "llm.enabled=true but gemini.api-key is empty";

    private NaturalLanguagePort wire(boolean enabled, String apiKey) {
        ForecastingProperties properties = new ForecastingProperties();
        properties.getLlm().setEnabled(enabled);
        return new AiForecastingConfiguration().naturalLanguagePort(properties, new FallbackMessagePolicy(),
                Clock.systemUTC(), apiKey, "gemini-2.5-flash");
    }

    @Test
    void disabledUsesTheTemplate(CapturedOutput output) {
        assertThat(wire(false, "")).isInstanceOf(FallbackOnlyRedactionAdapter.class);
        assertThat(output).contains("LLM disabled, using template redaction").doesNotContain(MISSING_KEY_WARNING);
    }

    @Test
    void enabledWithoutKeyWarnsOnceAndUsesTheTemplate(CapturedOutput output) {
        assertThat(wire(true, "")).isInstanceOf(FallbackOnlyRedactionAdapter.class);
        assertThat(wire(true, "   ")).isInstanceOf(FallbackOnlyRedactionAdapter.class);
        assertThat(wire(true, null)).isInstanceOf(FallbackOnlyRedactionAdapter.class);
        assertThat(output).contains(MISSING_KEY_WARNING);
    }

    @Test
    void enabledWithKeyUsesGeminiAndNeverLogsTheKey(CapturedOutput output) {
        String fakeKey = "test-key-not-real-0123456789";

        assertThat(wire(true, fakeKey)).isInstanceOf(GeminiRedactionAdapter.class);
        assertThat(output).contains("Gemini redaction enabled").doesNotContain(fakeKey);
    }
}
