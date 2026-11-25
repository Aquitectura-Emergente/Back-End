package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.Finding;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.FindingLevel;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.InsightPriority;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.PositionRef;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.Recommendation;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.WorkerNames;
import com.emsafe.aiforecasting.domain.ports.PeriodInsightsPort;
import com.emsafe.aiforecasting.domain.services.InsightsFallbackPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Análisis con IA" del periodo con Gemini ({@code gemini.api-key}, {@code gemini.model}), con
 * respuesta JSON estructurada. Envía solo datos agregados con seudónimos
 * ({@link GeminiInsightsPromptBuilder}) y, al volver, valida la estructura y las longitudes y
 * reemplaza los seudónimos: en los textos, por el nombre del operario ({@link WorkerNames}, que
 * nunca entra en el prompt); en {@code operariosAfectados}, por el {@code workerId}. Ante key ausente, error HTTP, timeout, respuesta vacía
 * o inválida devuelve el análisis de {@link InsightsFallbackPolicy}: nunca falla por el LLM.
 */
public class GeminiInsightsAdapter implements PeriodInsightsPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiInsightsAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_OUTPUT_TOKENS = 4096;

    private final GeminiClient gemini;
    private final InsightsFallbackPolicy fallback;
    private final Clock clock;

    public GeminiInsightsAdapter(RestTemplate restTemplate, String apiKey, String model,
                                 InsightsFallbackPolicy fallback, Clock clock) {
        this.gemini = new GeminiClient(restTemplate, apiKey, model);
        this.fallback = fallback;
        this.clock = clock;
    }

    @Override
    public PeriodInsights analyze(AnalyticsSummary summary, WorkerNames workerNames) {
        WorkerNames people = workerNames == null ? WorkerNames.none() : workerNames;
        if (!gemini.hasKey()) {
            log.warn("gemini.api-key is not configured; using fallback period insights");
            return fallback.analyze(summary, people, clock.instant());
        }
        try {
            InsightsPseudonyms names = new InsightsPseudonyms(summary);
            String text = gemini.generate(GeminiInsightsPromptBuilder.SYSTEM_INSTRUCTION,
                    GeminiInsightsPromptBuilder.build(summary, names),
                    Map.of("temperature", 0.2, "maxOutputTokens", MAX_OUTPUT_TOKENS,
                            "responseMimeType", "application/json",
                            "responseSchema", GeminiInsightsPromptBuilder.RESPONSE_SCHEMA));
            return parse(text, summary, names, people);
        } catch (InvalidInsightsResponseException | ForecastingRuleException e) {
            log.warn("Gemini period insights were invalid; using fallback: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Gemini period insights failed; using fallback: {}", e.toString());
        }
        return fallback.analyze(summary, people, clock.instant());
    }

    /**
     * Valida el JSON del LLM (estructura y longitudes, con los seudónimos) y lo mapea: los textos con los
     * nombres, recortados si estos los alargan, y los afectados con los IDs reales.
     */
    PeriodInsights parse(String text, AnalyticsSummary s, InsightsPseudonyms names, WorkerNames people) {
        if (text == null || text.isBlank()) {
            throw new InvalidInsightsResponseException("empty response");
        }
        JsonNode root;
        try {
            root = JSON.readTree(stripFences(text));
        } catch (JsonProcessingException e) {
            throw new InvalidInsightsResponseException("response is not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidInsightsResponseException("response is not a JSON object");
        }

        String resumen = restore(names, people, text(root, "resumen", PeriodInsights.MAX_SUMMARY_LENGTH),
                PeriodInsights.MAX_SUMMARY_LENGTH);
        List<Finding> findings = new ArrayList<>();
        for (JsonNode f : array(root, "hallazgos", PeriodInsights.MAX_FINDINGS)) {
            findings.add(new Finding(restore(names, people, text(f, "texto", PeriodInsights.MAX_FINDING_LENGTH),
                    PeriodInsights.MAX_FINDING_LENGTH),
                    enumValue(f, "nivel", FindingLevel.class)));
        }
        List<Recommendation> recommendations = new ArrayList<>();
        for (JsonNode r : array(root, "recomendaciones", PeriodInsights.MAX_RECOMMENDATIONS)) {
            Set<Long> workers = new LinkedHashSet<>();
            for (String alias : strings(r, "operariosAfectados")) workers.add(names.resolveWorker(alias));
            Set<PositionRef> positions = new LinkedHashSet<>();
            for (String alias : strings(r, "puestosAfectados")) positions.add(names.resolvePosition(alias));
            recommendations.add(new Recommendation(
                    restore(names, people, text(r, "accion", PeriodInsights.MAX_ACTION_LENGTH),
                            PeriodInsights.MAX_ACTION_LENGTH),
                    restore(names, people, text(r, "motivo", PeriodInsights.MAX_REASON_LENGTH),
                            PeriodInsights.MAX_REASON_LENGTH),
                    enumValue(r, "prioridad", InsightPriority.class),
                    List.copyOf(workers), List.copyOf(positions)));
        }
        String calidad = restore(names, people, text(root, "calidadDatos", PeriodInsights.MAX_DATA_QUALITY_LENGTH),
                PeriodInsights.MAX_DATA_QUALITY_LENGTH);
        // El dominio vuelve a validar las longitudes.
        return new PeriodInsights(s.organizationId(), s.from(), s.to(), s.zone(), RedactionSource.LLM,
                gemini.model(), clock.instant(), resumen, findings, recommendations, calidad);
    }

    private static String restore(InsightsPseudonyms names, WorkerNames people, String text, int max) {
        return PeriodInsights.fit(names.restore(text, people), max);
    }

    private static String stripFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstLine = t.indexOf('\n');
            int closing = t.lastIndexOf("```");
            if (firstLine > 0 && closing > firstLine) {
                t = t.substring(firstLine + 1, closing).trim();
            }
        }
        return t;
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new InvalidInsightsResponseException(field + " must be a non-empty string");
        }
        String trimmed = value.asText().trim();
        if (trimmed.length() > max) {
            throw new InvalidInsightsResponseException(field + " exceeds " + max + " characters");
        }
        return trimmed;
    }

    private static List<JsonNode> array(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > max) {
            throw new InvalidInsightsResponseException(field + " must have between 1 and " + max + " items");
        }
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isObject()) {
                throw new InvalidInsightsResponseException(field + " items must be objects");
            }
            items.add(item);
        }
        return items;
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new InvalidInsightsResponseException(field + " must be an array");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new InvalidInsightsResponseException(field + " items must be strings");
            }
            out.add(item.asText());
        }
        return out;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidInsightsResponseException(field + " is required");
        }
        try {
            return Enum.valueOf(type, value.asText().trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidInsightsResponseException(field + " has an unknown value");
        }
    }
}
