package com.emsafe.occupationalhealth.application.events;

import java.time.LocalDateTime;
import java.util.List;

/** Mitigation → interesados: la medida se decidió en contra (informe §5.6.3). */
public record MitigationActionRejected(
        EventEnvelope envelope,
        Long actionId,
        List<Long> workerIds,
        String reason,
        LocalDateTime rejectedAt,
        Long rejectedBy) {
}
