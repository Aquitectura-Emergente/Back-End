package com.emsafe.clientportal.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** A chatbot question from the mobile app, with optional conversation history. */
public record ChatRequest(
        @NotBlank(message = "message is required")
        String message,
        List<ChatTurn> history
) {
    /** One previous conversation turn: role = "user" | "model". */
    public record ChatTurn(String role, String text) {}
}
