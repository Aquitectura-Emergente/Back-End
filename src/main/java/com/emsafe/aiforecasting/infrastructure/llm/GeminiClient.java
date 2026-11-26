package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.LlmQuotaExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Llamada {@code generateContent} de Gemini compartida por la redacción y el análisis del periodo.
 *
 * <ul>
 *   <li>La key va en la cabecera {@code x-goog-api-key} y no en la URL, para que no quede en logs de
 *       acceso.</li>
 *   <li><b>Razonamiento al mínimo</b> ({@code generationConfig.thinkingConfig}), para que los modelos
 *       con razonamiento interno respondan dentro del timeout: {@code thinkingBudget = 0} en los
 *       {@code gemini-2.*} y {@code thinkingLevel = "minimal"} (luego {@code "low"}) en los demás.</li>
 *   <li>Si el modelo rechaza esa configuración (400 que menciona "thinking"), se reintenta enseguida
 *       con la siguiente opción y, al final, sin {@code thinkingConfig}. La opción rechazada se recuerda
 *       para las llamadas siguientes. Un timeout o cualquier otro error no se reintenta.</li>
 *   <li><b>Cuota agotada</b> (HTTP 429): se registra "Cuota de Gemini agotada" (sin la clave ni el cuerpo
 *       de la respuesta) y se lanza {@link LlmQuotaExceededException}. No se reintenta.</li>
 * </ul>
 *
 * Devuelve el texto de la primera candidata (sus partes que no son de razonamiento, concatenadas) o
 * {@code null} si no hay; los errores HTTP y de red se propagan.
 */
final class GeminiClient {

    static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    /** Configuración de razonamiento que se envía, de la más rápida a ninguna. */
    enum Thinking {
        BUDGET_ZERO(Map.of("thinkingBudget", 0)),
        LEVEL_MINIMAL(Map.of("thinkingLevel", "minimal")),
        LEVEL_LOW(Map.of("thinkingLevel", "low")),
        NONE(null);

        final Map<String, Object> config;

        Thinking(Map<String, Object> config) {
            this.config = config;
        }
    }

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String model;
    /** Opciones que quedan por probar; la primera es la que se envía. */
    private final AtomicReference<List<Thinking>> thinking;

    GeminiClient(RestTemplate restTemplate, String apiKey, String model) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.model = model;
        this.thinking = new AtomicReference<>(initialThinking(model));
    }

    static List<Thinking> initialThinking(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return m.startsWith("gemini-2") || m.startsWith("models/gemini-2")
                ? List.of(Thinking.BUDGET_ZERO, Thinking.NONE)
                : List.of(Thinking.LEVEL_MINIMAL, Thinking.LEVEL_LOW, Thinking.NONE);
    }

    boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    String model() {
        return model;
    }

    /** Opción de razonamiento que se enviará en la próxima llamada. */
    Thinking currentThinking() {
        return thinking.get().get(0);
    }

    String generate(String systemInstruction, String prompt, Map<String, Object> generationConfig) {
        while (true) {
            Thinking t = currentThinking();
            try {
                return post(systemInstruction, prompt, generationConfig, t);
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn("Cuota de Gemini agotada (HTTP 429) para el modelo {}: se usa el fallback. "
                        + "La cuota gratuita es por proyecto y por modelo.", model);
                throw new LlmQuotaExceededException(model);
            } catch (HttpClientErrorException.BadRequest e) {
                if (t == Thinking.NONE || !rejectsThinking(e)) {
                    throw e;
                }
                thinking.updateAndGet(options -> options.get(0) == t ? options.subList(1, options.size()) : options);
                log.info("Gemini model {} rejected thinkingConfig {}; retrying with {}", model, t, currentThinking());
            }
        }
    }

    static Map<String, Object> body(String systemInstruction, String prompt, Map<String, Object> generationConfig,
                                    Thinking t) {
        Map<String, Object> config = new HashMap<>(generationConfig);
        if (t.config != null) {
            config.put("thinkingConfig", t.config);
        }
        return Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", config);
    }

    private String post(String systemInstruction, String prompt, Map<String, Object> generationConfig, Thinking t) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(String.format(URL, model),
                new HttpEntity<>(body(systemInstruction, prompt, generationConfig, t), headers), Map.class);
        return extractText(response);
    }

    /** 400 por la configuración de razonamiento (y no, p. ej., por una key inválida). */
    private static boolean rejectsThinking(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        return body != null && body.toLowerCase(Locale.ROOT).contains("thinking");
    }

    private static String extractText(Map<String, Object> response) {
        if (response == null) return null;
        if (!(response.get("candidates") instanceof List<?> candidates) || candidates.isEmpty()) return null;
        if (!(candidates.get(0) instanceof Map<?, ?> first)) return null;
        if (!(first.get("content") instanceof Map<?, ?> content)) return null;
        if (!(content.get("parts") instanceof List<?> parts) || parts.isEmpty()) return null;
        StringBuilder text = new StringBuilder();
        for (Object p : parts) {
            if (p instanceof Map<?, ?> part && !Boolean.TRUE.equals(part.get("thought")) && part.get("text") != null) {
                text.append(part.get("text"));
            }
        }
        return text.isEmpty() ? null : text.toString();
    }
}
