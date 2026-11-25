package com.emsafe.aiforecasting.infrastructure.llm;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Llamada {@code generateContent} de Gemini compartida por la redacción y el análisis del periodo.
 * La key va en la cabecera {@code x-goog-api-key} y no en la URL, para que no quede en logs de
 * acceso. Devuelve el texto de la primera candidata (sus partes que no son de razonamiento,
 * concatenadas) o {@code null} si no hay; los errores HTTP y de red se propagan.
 */
final class GeminiClient {

    static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String model;

    GeminiClient(RestTemplate restTemplate, String apiKey, String model) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey;
        this.model = model;
    }

    boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    String model() {
        return model;
    }

    String generate(String systemInstruction, String prompt, Map<String, Object> generationConfig) {
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemInstruction))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", generationConfig);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
                String.format(URL, model), new HttpEntity<>(body, headers), Map.class);
        return extractText(response);
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
