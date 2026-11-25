package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.ports.NaturalLanguagePort;
import com.emsafe.aiforecasting.domain.services.FallbackMessagePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Redacción con Gemini ({@code gemini.api-key}, {@code gemini.model}). Usa un RestTemplate
 * propio con timeout. Ante key ausente, error, timeout o texto vacío devuelve la redacción de
 * {@link FallbackMessagePolicy}: la evaluación nunca falla por el LLM.
 */
public class GeminiRedactionAdapter implements NaturalLanguagePort {

    static final int MAX_TEXT_LENGTH = 2000;
    private static final Logger log = LoggerFactory.getLogger(GeminiRedactionAdapter.class);

    private final GeminiClient gemini;
    private final FallbackMessagePolicy fallback;
    private final Clock clock;

    public GeminiRedactionAdapter(RestTemplate restTemplate, String apiKey, String model,
                                  FallbackMessagePolicy fallback, Clock clock) {
        this.gemini = new GeminiClient(restTemplate, apiKey, model);
        this.fallback = fallback;
        this.clock = clock;
    }

    /** RestTemplate dedicado con el mismo timeout de conexión y de lectura. */
    public static RestTemplate restTemplateWithTimeout(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return new RestTemplate(factory);
    }

    @Override
    public NaturalLanguageRedaction redact(PreventiveAssessment assessment) {
        if (!gemini.hasKey()) {
            log.warn("gemini.api-key is not configured; using fallback redaction");
            return fallback.redact(assessment, clock.instant());
        }
        try {
            String text = call(GeminiPromptBuilder.build(assessment));
            if (text != null && !text.isBlank()) {
                String trimmed = text.trim();
                if (trimmed.length() > MAX_TEXT_LENGTH) {
                    trimmed = trimmed.substring(0, MAX_TEXT_LENGTH);
                }
                return new NaturalLanguageRedaction(trimmed, RedactionSource.LLM, clock.instant());
            }
            log.warn("Gemini returned an empty redaction for assessment {}; using fallback", assessment.getId());
        } catch (RuntimeException e) {
            log.warn("Gemini redaction failed for assessment {}; using fallback: {}",
                    assessment.getId(), e.toString());
        }
        return fallback.redact(assessment, clock.instant());
    }

    private String call(String prompt) {
        return gemini.generate(GeminiPromptBuilder.SYSTEM_INSTRUCTION, prompt,
                Map.of("temperature", 0.2, "maxOutputTokens", 400));
    }
}
