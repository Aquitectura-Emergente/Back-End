package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ModelVersion;
import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;
import com.emsafe.aiforecasting.domain.services.ExposureProjectionService;
import com.emsafe.aiforecasting.domain.services.FallbackMessagePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiRedactionAdapterTest {

    private static final Instant DAY = Instant.parse("2026-11-10T00:00:00Z");
    private static final String URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent";

    private final Clock clock = Clock.fixed(DAY.plusSeconds(12 * 3600), ZoneOffset.UTC);
    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private GeminiRedactionAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        adapter = new GeminiRedactionAdapter(restTemplate, "test-key", "gemini-test",
                new FallbackMessagePolicy(), clock);
    }

    private static PreventiveAssessment highWithRotation() {
        Instant start = DAY.plusSeconds(8 * 3600);
        ExposureProjection p = new ExposureProjectionService().project(new BigDecimal("7200"),
                new BigDecimal("12000"), start, start.plus(Duration.ofHours(4)), start.plus(Duration.ofHours(8)));
        PreventiveAssessment a = PreventiveAssessment.project("6f1c0d3e-2b7a-4c55-9a1e-3f2d4b5c6a7e", 1L, 101L,
                "S-101", p, new ModelVersion("LINEAR_RATE", "1"), 7L, "w-1", start.plus(Duration.ofHours(4)));
        a.assess(CriticalityLevel.HIGH);
        a.recommend(new RotationRecommendation(RecommendationType.ROTATION, 101L, 200L, 10L, 20L,
                new BigDecimal("5760"), "rotar"));
        return a;
    }

    private static String geminiReply(String text) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":" + text + "}]}}]}";
    }

    @Test
    void successUsesTheLlmText() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("101"),
                        org.hamcrest.Matchers.containsString("HIGH"),
                        org.hamcrest.Matchers.containsString("60.00 %"),
                        org.hamcrest.Matchers.containsString("120.00 %"),
                        org.hamcrest.Matchers.containsString("14:40 UTC"),
                        org.hamcrest.Matchers.containsString("ROTATION"),
                        org.hamcrest.Matchers.containsString("200"))))
                .andRespond(withSuccess(geminiReply("\"  Riesgo alto: rotar con el operario 200.  \""),
                        MediaType.APPLICATION_JSON));

        NaturalLanguageRedaction r = adapter.redact(highWithRotation());

        server.verify();
        assertThat(r.generatedBy()).isEqualTo(RedactionSource.LLM);
        assertThat(r.text()).isEqualTo("Riesgo alto: rotar con el operario 200.");
        assertThat(r.generatedAt()).isEqualTo(clock.instant());
    }

    @Test
    void timeoutFallsBack() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        NaturalLanguageRedaction r = adapter.redact(highWithRotation());

        assertThat(r.generatedBy()).isEqualTo(RedactionSource.FALLBACK);
        assertThat(r.text()).contains("101").contains("120.00 %");
    }

    @Test
    void emptyTextFallsBack() {
        server.expect(requestTo(URL)).andRespond(withSuccess(geminiReply("\"   \""), MediaType.APPLICATION_JSON));

        assertThat(adapter.redact(highWithRotation()).generatedBy()).isEqualTo(RedactionSource.FALLBACK);
    }

    @Test
    void responseWithoutCandidatesFallsBack() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        assertThat(adapter.redact(highWithRotation()).generatedBy()).isEqualTo(RedactionSource.FALLBACK);
    }

    @Test
    void serverErrorFallsBack() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(adapter.redact(highWithRotation()).generatedBy()).isEqualTo(RedactionSource.FALLBACK);
    }

    @Test
    void missingApiKeyFallsBackWithoutCallingGemini() {
        GeminiRedactionAdapter noKey = new GeminiRedactionAdapter(restTemplate, " ", "gemini-test",
                new FallbackMessagePolicy(), clock);

        assertThat(noKey.redact(highWithRotation()).generatedBy()).isEqualTo(RedactionSource.FALLBACK);
        server.verify();
    }

    @Test
    void overlongReplyIsTruncated() {
        String longText = "a".repeat(GeminiRedactionAdapter.MAX_TEXT_LENGTH + 500);
        server.expect(requestTo(URL))
                .andRespond(withSuccess(geminiReply("\"" + longText + "\""), MediaType.APPLICATION_JSON));

        assertThat(adapter.redact(highWithRotation()).text()).hasSize(GeminiRedactionAdapter.MAX_TEXT_LENGTH);
    }

    @Test
    void timeoutIsAppliedToTheRequestFactory() throws IOException {
        RestTemplate configured = GeminiRedactionAdapter.restTemplateWithTimeout(Duration.ofMillis(1234));

        assertThat(configured.getRequestFactory())
                .isInstanceOf(org.springframework.http.client.SimpleClientHttpRequestFactory.class)
                .extracting("connectTimeout", "readTimeout").containsExactly(1234, 1234);
    }

    @Test
    void promptContainsOnlyIdsAndNumbers() {
        String prompt = GeminiPromptBuilder.build(highWithRotation());

        assertThat(prompt)
                .contains("operario 101").contains("sesión S-101").contains("HIGH")
                .contains("60.00 %").contains("120.00 %").contains("16:00 UTC").contains("14:40 UTC")
                .contains("ROTATION").contains("operario 200")
                .doesNotContain("null");
    }
}
