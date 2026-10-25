package com.emsafe.clientportal.application;

import com.emsafe.clientportal.interfaces.rest.dto.ChatReplyDto;
import com.emsafe.clientportal.interfaces.rest.dto.ChatRequest;
import com.emsafe.clientportal.interfaces.rest.dto.ClientDeviceDto;
import com.emsafe.radiationmonitoring.domain.services.RadiationLevel;
import com.emsafe.shared.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Astra" — the EMSafe assistant (US10). Proxies chat requests to the Gemini
 * API so the key never ships inside the mobile app, and grounds every answer
 * with the client's real sensor data (smart-edge levels, µT thresholds).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final int MAX_HISTORY_TURNS = 10;

    private final ClientService clientService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.5-flash}")
    private String model;

    public ChatReplyDto chat(Long clientId, ChatRequest req) {
        if (!StringUtils.hasText(apiKey)) {
            throw new BadRequestException("The assistant is not configured on this server.");
        }

        // Conversation: recent history + the new user message.
        List<Map<String, Object>> contents = new ArrayList<>();
        if (req.history() != null) {
            req.history().stream()
                    .skip(Math.max(0, req.history().size() - MAX_HISTORY_TURNS))
                    .forEach(turn -> contents.add(Map.of(
                            "role", "model".equalsIgnoreCase(turn.role()) ? "model" : "user",
                            "parts", List.of(Map.of("text", turn.text() == null ? "" : turn.text()))
                    )));
        }
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", req.message()))
        ));

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", buildSystemPrompt(clientId)))),
                "contents", contents,
                "generationConfig", Map.of(
                        "temperature", 0.6,
                        "maxOutputTokens", 700
                )
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String url = String.format(GEMINI_URL, model, apiKey);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), Map.class);

            String reply = extractText(response);
            if (!StringUtils.hasText(reply)) {
                throw new BadRequestException("The assistant could not produce an answer. Try again.");
            }
            return new ChatReplyDto(reply.trim());
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Gemini call failed: {}", e.getMessage());
            throw new BadRequestException("The assistant is unavailable right now. Try again in a moment.");
        }
    }

    /** Grounds the model with EMSafe context + the client's live sensor snapshot. */
    private String buildSystemPrompt(Long clientId) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Astra, the assistant inside the EMSafe mobile app. ")
          .append("EMSafe monitors electromagnetic field exposure (magnetic field, microtesla µT) ")
          .append("with IoT sensors. Levels (ICNIRP 50 Hz reference): safe < ")
          .append((int) RadiationLevel.CAUTION_UT).append(" µT, caution ")
          .append((int) RadiationLevel.CAUTION_UT).append("–")
          .append((int) RadiationLevel.DANGER_UT).append(" µT, danger ≥ ")
          .append((int) RadiationLevel.DANGER_UT).append(" µT. ")
          .append("Each sensor may control a smart plug (relay) that can cut power; ")
          .append("the user can toggle it from the Monitor tab. ")
          .append("Answer briefly (max ~120 words), in the same language the user writes ")
          .append("(Spanish or English). Be practical and reassuring; do not invent data. ")
          .append("If asked about medical issues, recommend consulting a professional.\n\n");

        try {
            List<ClientDeviceDto> devices = clientService.getDevices(clientId);
            sb.append("Current sensors of this user:\n");
            if (devices.isEmpty()) {
                sb.append("- (no sensors assigned yet)\n");
            }
            for (ClientDeviceDto d : devices) {
                sb.append("- ").append(d.name())
                  .append(" [").append(d.location() == null ? "no zone" : d.location()).append("]: ");
                if (d.latestValue() != null) {
                    sb.append(d.latestValue()).append(" µT (").append(d.latestLevel()).append(")");
                } else {
                    sb.append("no readings yet");
                }
                if (d.plug() != null) sb.append(", plug ").append(d.plug());
                sb.append('\n');
            }
        } catch (Exception e) {
            // Context is best-effort: the assistant still works without it.
            log.debug("Could not attach sensor context: {}", e.getMessage());
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> response) {
        if (response == null) return null;
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) return null;
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) return null;
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) return null;
        Object text = parts.get(0).get("text");
        return text != null ? text.toString() : null;
    }
}
