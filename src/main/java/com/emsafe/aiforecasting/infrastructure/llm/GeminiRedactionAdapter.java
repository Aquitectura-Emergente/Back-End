package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.ports.NaturalLanguagePort;
import com.emsafe.aiforecasting.domain.services.FallbackMessagePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Redacción con Gemini ({@code gemini.api-key}, {@code gemini.model}). Usa un RestTemplate
 * propio con timeout. Ante key ausente, error, timeout o texto vacío devuelve la redacción de
 * {@link FallbackMessagePolicy}: la evaluación nunca falla por el LLM.
 */
public class GeminiRedactionAdapter implements NaturalLanguagePort {

    static final int MAX_TEXT_LENGTH = 2000;
    private static final Logger log = LoggerFactory.getLogger(GeminiRedactionAdapter.class);
    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String model;
    private final FallbackMessagePolicy fallback;
    private final Clock clock;

    public GeminiRedactionAdapter(RestTemplate restTemplate, String apiKey, String model,
                                  FallbackMessagePolicy fallback, Clock clock) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.model = model;
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
        if (apiKey == null || apiKey.isBlank()) {
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
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", GeminiPromptBuilder.SYSTEM_INSTRUCTION))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("temperature", 0.2, "maxOutputTokens", 400));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // La key va en cabecera y no en la URL, para que no quede en logs de acceso.
        headers.set("x-goog-api-key", apiKey);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
                String.format(URL, model), new HttpEntity<>(body, headers), Map.class);
        return extractText(response);
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> response) {
        if (response == null) return null;
        if (!(response.get("candidates") instanceof List<?> candidates) || candidates.isEmpty()) return null;
        if (!(candidates.get(0) instanceof Map<?, ?> first)) return null;
        if (!(first.get("content") instanceof Map<?, ?> content)) return null;
        if (!(content.get("parts") instanceof List<?> parts) || parts.isEmpty()) return null;
        if (!(parts.get(0) instanceof Map<?, ?> part)) return null;
        Object text = ((Map<String, Object>) part).get("text");
        return text == null ? null : text.toString();
    }
}
