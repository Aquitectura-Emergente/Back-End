package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.infrastructure.llm.GeminiClient.Thinking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** thinkingConfig al mínimo y reintento sin él si el modelo no lo acepta. */
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
    void aTimeoutIsNotRetried() {
        GeminiClient c = client("gemini-3.8-flash");
        server.expect(ExpectedCount.once(), requestTo(url("gemini-3.8-flash"))).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });

        assertThatThrownBy(() -> c.generate("sys", "prompt", CONFIG)).isInstanceOf(ResourceAccessException.class);
        server.verify();
    }
}
