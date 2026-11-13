package com.emsafe.aiforecasting.application;

/**
 * Resultado de procesar un evento del inbox.
 *
 * @param duplicate    el eventId ya estaba procesado y no hubo efectos
 * @param assessmentId evaluación creada o recalculada, si la hubo
 */
public record InboxOutcome(String eventId, boolean duplicate, String assessmentId) {

    public static InboxOutcome processed(String eventId, String assessmentId) {
        return new InboxOutcome(eventId, false, assessmentId);
    }

    public static InboxOutcome duplicateOf(String eventId) {
        return new InboxOutcome(eventId, true, null);
    }
}
