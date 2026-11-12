package com.emsafe.occupationalhealth.interfaces.rest.dto;

import com.emsafe.occupationalhealth.domain.model.MitigationTrigger;

public record TriggerResource(String sourceEventId, String kind, String detectedAt) {
    public static TriggerResource from(MitigationTrigger t) {
        if (t == null) return null;
        return new TriggerResource(
                t.getSourceEventId(),
                t.getKind() != null ? t.getKind().name() : null,
                t.getDetectedAt() != null ? t.getDetectedAt().toString() : null);
    }
}
