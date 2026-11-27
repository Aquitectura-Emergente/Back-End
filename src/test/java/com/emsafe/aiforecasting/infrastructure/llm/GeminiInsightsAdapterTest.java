package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.FindingLevel;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.InsightPriority;
import com.emsafe.aiforecasting.domain.model.PeriodInsights.PositionRef;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.WorkerNames;
import com.emsafe.aiforecasting.domain.services.InsightsFallbackPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.emsafe.aiforecasting.domain.InsightsFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiInsightsAdapterTest {

    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-22T15:00:00Z"), ZoneOffset.UTC);
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private GeminiInsightsAdapter adapter;
    private final List<String> requestBodies = new ArrayList<>();
    /** Nombres de IAM (resueltos en el backend). W3 no tiene nombre: "operario #id". */
    private static final String NAME_1 = "Lucía Secreta Mendoza", NAME_2 = "Carlos Privado Rojas",
            NAME_4 = "Jorge Oculto Salas";
    private static final WorkerNames NAMES = new WorkerNames(java.util.Map.of(W1, NAME_1, W2, NAME_2, W4, NAME_4));

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        adapter = new GeminiInsightsAdapter(restTemplate, "test-key", "gemini-test", new InsightsFallbackPolicy(), clock);
    }

    /** Respuesta de Gemini cuyo texto es {@code payload} (el JSON que escribió el modelo). */
    private static String geminiReply(String payload) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("candidates", List.of(java.util.Map.of("content",
                java.util.Map.of("parts", List.of(java.util.Map.of("text", payload)))))));
    }

    private void respondWith(String payload) throws Exception {
        String reply = geminiReply(payload);
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(request -> requestBodies.add(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(reply, MediaType.APPLICATION_JSON));
    }

    /** Operario A = W1, B = W2, C = W3, D = W4 (orden del resumen); Puesto A = P1, B = P2. */
    private static final String VALID = """
            {"resumen": "El Operario A superó el límite una vez y el Operario B tiene una proyección CRITICAL.",
             "hallazgos": [
               {"texto": "Operario A: 1 jornada sobre el límite (103.00 %).", "nivel": "CRITICO"},
               {"texto": "El Puesto A concentra el mayor índice promedio; le sigue Puesto B.", "nivel": "ATENCION"},
               {"texto": "La tendencia semanal sube de 6000 a 7800 uT·s.", "nivel": "INFO"}],
             "recomendaciones": [
               {"accion": "Rotar al Operario D con el Operario C.", "motivo": "Proyección HIGH del Operario D.",
                "prioridad": "ALTA", "operariosAfectados": ["Operario D", "C"], "puestosAfectados": []},
               {"accion": "Revisar el blindaje del Puesto A.", "motivo": "Mayor índice promedio.",
                "prioridad": "MEDIA", "operariosAfectados": ["Operario A"], "puestosAfectados": ["Puesto A", "Puesto B"]}],
             "calidadDatos": "2 jornadas con cobertura incompleta."}
            """;

    @Test
    void validJsonIsMappedWithNamesInTheTextsAndIdsInTheAffectedLists() throws Exception {
        respondWith(VALID);

        PeriodInsights i = adapter.analyze(rich(), NAMES);

        server.verify();
        assertThat(i.generatedBy()).isEqualTo(RedactionSource.LLM);
        assertThat(i.model()).isEqualTo("gemini-test");
        assertThat(i.generatedAt()).isEqualTo(clock.instant());
        assertThat(i.organizationId()).isEqualTo(ORG);
        assertThat(i.from()).isEqualTo(FROM);
        assertThat(i.to()).isEqualTo(TO);
        assertThat(i.summary()).isEqualTo(NAME_1 + " superó el límite una vez y " + NAME_2
                + " tiene una proyección CRITICAL.");
        assertThat(i.findings()).extracting(PeriodInsights.Finding::level)
                .containsExactly(FindingLevel.CRITICO, FindingLevel.ATENCION, FindingLevel.INFO);
        assertThat(i.findings().get(0).text()).startsWith(NAME_1 + ":");
        assertThat(i.findings().get(1).text())
                .isEqualTo("El puesto «" + POSITION_NAME + "» concentra el mayor índice promedio; le sigue el puesto #"
                        + P2 + ".");
        assertThat(i.recommendations().get(0).priority()).isEqualTo(InsightPriority.ALTA);
        // con nombre, el artículo se ajusta ("al" → "a"); sin nombre, "operario #id"
        assertThat(i.recommendations().get(0).action()).isEqualTo("Rotar a " + NAME_4 + " con el Operario #" + W3 + ".");
        assertThat(i.recommendations().get(0).reason()).isEqualTo("Proyección HIGH de " + NAME_4 + ".");
        assertThat(i.recommendations().get(0).workerIds()).containsExactly(W4, W3);
        assertThat(i.recommendations().get(1).workerIds()).containsExactly(W1);
        assertThat(i.recommendations().get(1).positions())
                .containsExactly(new PositionRef(P1, POSITION_NAME), new PositionRef(P2, null));
        assertThat(i.dataQuality()).isEqualTo("2 jornadas con cobertura incompleta.");
        // ningún seudónimo queda en el texto devuelto
        assertThat(i.summary() + i.findings() + i.recommendations()).doesNotContainPattern("(?i)(operario|puesto) [A-D]\\b");
    }

    @Test
    void thePromptCarriesOnlyAggregatesAndPseudonyms() throws Exception {
        respondWith(VALID);

        adapter.analyze(rich(), NAMES);

        assertThat(requestBodies).hasSize(1);
        String body = requestBodies.get(0);
        String prompt = JSON.readTree(body).at("/contents/0/parts/0/text").asText();
        String system = JSON.readTree(body).at("/system_instruction/parts/0/text").asText();
        for (String sent : List.of(body, prompt)) {
            assertThat(sent)
                    .doesNotContain(String.valueOf(ORG))
                    .doesNotContain(String.valueOf(W1)).doesNotContain(String.valueOf(W2))
                    .doesNotContain(String.valueOf(W3)).doesNotContain(String.valueOf(W4))
                    .doesNotContain(String.valueOf(P1)).doesNotContain(String.valueOf(P2))
                    .doesNotContain(POSITION_NAME).doesNotContain("SECRET")
                    .doesNotContain("@").doesNotContain("test-key")
                    // los nombres de IAM solo se usan al volver, nunca en el prompt
                    .doesNotContain(NAME_1).doesNotContain(NAME_2).doesNotContain(NAME_4)
                    .doesNotContain("Lucía").doesNotContain("Secreta").doesNotContain("Oculto");
        }
        assertThat(prompt)
                .contains("Operario A: 10; 9,800 µT·s; 12,360 µT·s; 103 %; 5; 2; 1")
                .contains("Operario B: CRITICAL; índice actual 90.8 % del límite")
                .contains("182 % del límite").contains("2026-11-22 11:47")
                .contains("Operario D: HIGH").contains("ROTATION con Operario C")
                .contains("Puesto A: 10; 9,800 µT·s").contains("Puesto B: 20; 5,500 µT·s")
                .contains("2026-W47: 10; 7,800; 12,360; 2; 1")
                .contains("Jornadas cerradas: 30; operarios: 3")
                .contains("Con cobertura incompleta del dosímetro: 2")
                .contains("Seudónimos válidos de operarios: Operario A, Operario B, Operario C, Operario D.")
                .doesNotContain("null");
        assertThat(system).contains("SOLO los datos").contains("No inventes cifras").contains("rotación")
                .contains("blindaje").contains("capacitación").contains("JSON");
        assertThat(JSON.readTree(body).at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(JSON.readTree(body).at("/generationConfig/responseSchema/required").toString())
                .contains("resumen").contains("hallazgos").contains("recomendaciones").contains("calidadDatos");
    }

    @Test
    void withoutNamesTheTextsSayOperarioHashId() throws Exception {
        respondWith(VALID);

        PeriodInsights i = adapter.analyze(rich(), WorkerNames.none());

        assertThat(i.summary()).isEqualTo("El Operario #" + W1 + " superó el límite una vez y el Operario #" + W2
                + " tiene una proyección CRITICAL.");
        assertThat(i.recommendations().get(0).action())
                .isEqualTo("Rotar al Operario #" + W4 + " con el Operario #" + W3 + ".");
    }

    @Test
    void longNamesAreTrimmedToTheLimitInsteadOfDiscardingTheLlmAnswer() throws Exception {
        respondWith(VALID);
        String longName = "N".repeat(PeriodInsights.MAX_FINDING_LENGTH);

        PeriodInsights i = adapter.analyze(rich(), new WorkerNames(java.util.Map.of(W1, longName)));

        assertThat(i.generatedBy()).isEqualTo(RedactionSource.LLM);
        assertThat(i.findings().get(0).text()).hasSize(PeriodInsights.MAX_FINDING_LENGTH).endsWith("…");
    }

    @Test
    void jsonInsideMarkdownFencesIsAccepted() throws Exception {
        respondWith("```json\n" + VALID + "\n```");

        assertThat(adapter.analyze(rich(), NAMES).generatedBy()).isEqualTo(RedactionSource.LLM);
    }

    @Test
    void notJsonFallsBack() throws Exception {
        respondWith("Claro, aquí tienes el análisis: el operario A está bien.");

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void missingFieldFallsBack() throws Exception {
        respondWith(VALID.replace("\"calidadDatos\": \"2 jornadas con cobertura incompleta.\"", "\"otro\": 1"));

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void unknownLevelFallsBack() throws Exception {
        respondWith(VALID.replace("\"CRITICO\"", "\"URGENTE\""));

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void unknownPseudonymFallsBack() throws Exception {
        respondWith(VALID.replace("[\"Operario A\"]", "[\"Operario Z\"]"));

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void unknownPseudonymInTextFallsBack() throws Exception {
        respondWith(VALID.replace("Revisar el blindaje del Puesto A.", "Revisar el blindaje del Puesto Q."));

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void overlongTextFallsBack() throws Exception {
        respondWith(VALID.replace("2 jornadas con cobertura incompleta.",
                "x".repeat(PeriodInsights.MAX_DATA_QUALITY_LENGTH + 1)));

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void tooManyFindingsFallBack() throws Exception {
        String finding = "{\"texto\": \"t\", \"nivel\": \"INFO\"}";
        String many = String.join(",", java.util.Collections.nCopies(PeriodInsights.MAX_FINDINGS + 1, finding));
        respondWith(VALID.replaceFirst("(?s)\"hallazgos\": \\[.*?\\],\\s*\"recomendaciones\"",
                "\"hallazgos\": [" + many + "], \"recomendaciones\""));

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void emptyFindingsFallBack() throws Exception {
        respondWith(VALID.replaceFirst("(?s)\"hallazgos\": \\[.*?\\],\\s*\"recomendaciones\"",
                "\"hallazgos\": [], \"recomendaciones\""));

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void timeoutFallsBack() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void quotaExceededIsPropagatedSoTheCallerCachesTheFallbackBriefly() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.analyze(rich(), NAMES))
                .isInstanceOf(com.emsafe.aiforecasting.domain.model.LlmQuotaExceededException.class)
                .hasMessageNotContaining("test-key");
    }

    @Test
    void serverErrorFallsBack() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void responseWithoutCandidatesFallsBack() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        assertFallback(adapter.analyze(rich(), NAMES));
    }

    @Test
    void missingApiKeyFallsBackWithoutCallingGemini() {
        GeminiInsightsAdapter noKey = new GeminiInsightsAdapter(restTemplate, " ", "gemini-test",
                new InsightsFallbackPolicy(), clock);

        assertFallback(noKey.analyze(rich(), NAMES));
        server.verify();
    }

    @Test
    void pseudonymLettersContinuePastZ() {
        assertThat(InsightsPseudonyms.letters(0)).isEqualTo("A");
        assertThat(InsightsPseudonyms.letters(25)).isEqualTo("Z");
        assertThat(InsightsPseudonyms.letters(26)).isEqualTo("AA");
        assertThat(InsightsPseudonyms.letters(27)).isEqualTo("AB");
    }

    private void assertFallback(PeriodInsights i) {
        assertThat(i.generatedBy()).isEqualTo(RedactionSource.FALLBACK);
        assertThat(i.model()).isNull();
        assertThat(i).isEqualTo(new InsightsFallbackPolicy().analyze(rich(), NAMES, clock.instant()));
    }
}
