package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.infrastructure.llm.GeminiClient.Thinking;
import com.emsafe.aiforecasting.domain.model.LlmQuotaExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** thinkingConfig al mínimo y reintento sin él si el modelo no lo acepta; cuota agotada (429). */
@ExtendWith(OutputCaptureExtension.class)
class GeminiClientTest {

    private static final String OK = "{\"candidates\":[{\"content\":{\"parts\":["
            + "{\"text\":\"pensando…\",\"thought\":true},{\"text\":\"respuesta\"}]}}]}";
    private static final String THINKING_REJECTED = "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\","
            + "\"message\":\"Thinking level is not supported for this model.\"}}";
    private static final String BAD_KEY = "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\","
            + "\"message\":\"API key not valid. Please pass a valid API key.\"}}";
    private static final Map<String, Object> CONFIG = Map.of("temperature", 0.2, "maxOutputTokens", 400);

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    private GeminiClient client(String model) {
        return new GeminiClient(restTemplate, "test-key", model);
    }

    private static String url(String model) {
        return String.format(GeminiClient.URL, model);
    }

    @Test
    void gemini2ModelsGetAThinkingBudgetOfZero() {
        server.expect(requestTo(url("gemini-2.5-flash")))
                .andExpect(jsonPath("$.generationConfig.thinkingConfig.thinkingBudget").value(0))
                .andExpect(jsonPath("$.generationConfig.thinkingConfig.thinkingLevel").doesNotExist())
                .andExpect(jsonPath("$.generationConfig.temperature").value(0.2))
                .andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(400))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertThat(client("gemini-2.5-flash").generate("sys", "prompt", CONFIG)).isEqualTo("respuesta");
        server.verify();
    }

    @Test
    void newerModelsGetTheMinimalThinkingLevel() {
        server.expect(requestTo(url("gemini-3.8-flash")))
                .andExpect(jsonPath("$.generationConfig.thinkingConfig.thinkingLevel").value("minimal"))
                .andExpect(jsonPath("$.generationConfig.thinkingConfig.thinkingBudget").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertThat(client("gemini-3.8-flash").generate("sys", "prompt", CONFIG)).isEqualTo("respuesta");
        server.verify();
    }

    @Test
    void whenTheModelRejectsThinkingItRetriesDownToNoThinkingConfigAndRemembersIt() {
        GeminiClient c = client("gemini-3.8-pro");
        server.expect(jsonPath("$.generationConfig.thinkingConfig.thinkingLevel").value("minimal"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(THINKING_REJECTED));
        server.expect(jsonPath("$.generationConfig.thinkingConfig.thinkingLevel").value("low"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body(THINKING_REJECTED));
        server.expect(jsonPath("$.generationConfig.thinkingConfig").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        // la siguiente llamada ya va sin thinkingConfig, sin volver a probar
        server.expect(jsonPath("$.generationConfig.thinkingConfig").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        assertThat(c.generate("sys", "prompt", CONFIG)).isEqualTo("respuesta");
        assertThat(c.currentThinking()).isEqualTo(Thinking.NONE);
        assertThat(c.generate("sys", "prompt", CONFIG)).isEqualTo("respuesta");
        server.verify();
    }

    @Test
    void aRejectedBudgetFallsBackToNoThinkingConfig() {
        GeminiClient c = client("gemini-2.5-pro");
        server.expect(jsonPath("$.generationConfig.thinkingConfig.thinkingBudget").value(0))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Budget 0 is invalid. This model only works in thinking mode.\"}}"));
        server.expect(jsonPath("$.generationConfig.thinkingConfig").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        // el mensaje no dice "thinking config" pero sí "thinking"
        assertThat(c.generate("sys", "prompt", CONFIG)).isEqualTo("respuesta");
        server.verify();
    }

    @Test
    void anotherBadRequestIsNotRetried() {
        GeminiClient c = client("gemini-3.8-flash");
        server.expect(ExpectedCount.once(), requestTo(url("gemini-3.8-flash")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(BAD_KEY));

        assertThatThrownBy(() -> c.generate("sys", "prompt", CONFIG)).isInstanceOf(HttpClientErrorException.class);
        assertThat(c.currentThinking()).isEqualTo(Thinking.LEVEL_MINIMAL);
        server.verify();
    }

    @Test
    void quotaExceededLogsClearlyWithoutTheKeyAndIsNotRetried(CapturedOutput output) {
        GeminiClient c = new GeminiClient(restTemplate, "secret-key-not-real-123", "gemini-3.5-flash-lite");
        server.expect(ExpectedCount.once(), requestTo(url("gemini-3.5-flash-lite")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\","
                                + "\"message\":\"You exceeded your current quota (thinking)\"}}"));

        assertThatThrownBy(() -> c.generate("sys", "prompt", CONFIG))
                .isInstanceOf(LlmQuotaExceededException.class)
                .hasMessageContaining("gemini-3.5-flash-lite")
                .hasMessageNotContaining("secret-key");
        server.verify();
        assertThat(c.currentThinking()).isEqualTo(Thinking.LEVEL_MINIMAL);
        assertThat(output).contains("Cuota de Gemini agotada").contains("gemini-3.5-flash-lite")
                .doesNotContain("secret-key-not-real-123").doesNotContain("RESOURCE_EXHAUSTED");
    }

    @Test
    void aTimeoutIsNotRetried() {
        GeminiClient c = client("gemini-3.8-flash");
        server.expect(ExpectedCount.once(), requestTo(url("gemini-3.8-flash"))).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        assertThatThrownBy(() -> c.generate("sys", "prompt", CONFIG)).isInstanceOf(ResourceAccessException.class);
        server.verify();
    }
}
