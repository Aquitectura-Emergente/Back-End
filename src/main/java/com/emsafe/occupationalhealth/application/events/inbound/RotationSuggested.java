package com.emsafe.occupationalhealth.application.events.inbound;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI Forecasting → Mitigation (informe §5.6.3).
 *
 * El agente proyecta una sobreexposición y propone rotar al operario. La
 * sugerencia no decide nada: aquí se valida y se deja pendiente de la
 * confirmación del SSO (RF29, US61).
 *
 * El evento trae candidatos pero no puestos. Eso es correcto y deliberado: una
 * rotación intercambia a dos operarios, así que los puestos de destino son los
 * que cada uno ocupa ahora, y eso lo sabe este contexto por sus WorkerShift —no
 * AI Forecasting.
 */
public record RotationSuggested(
        EventEnvelope envelope,
        String suggestionId,
        Long workerId,
        List<Long> candidateWorkerIds,
        LocalDateTime projectedExceedanceAt,
        String criticality,
        String rationale) {
}
