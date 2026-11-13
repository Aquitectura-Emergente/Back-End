package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * El hecho que originó la acción (informe §5.6.1).
 *
 * sourceEventId es lo que hace idempotente la ingesta: el UNIQUE
 * (organization_id, source_event_id) impide que una misma excedencia o
 * sugerencia abra dos acciones si el mensaje llega dos veces.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MitigationTrigger {

    @Column(name = "source_event_id", length = 64)
    private String sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", length = 30)
    private TriggerKind kind;

    @Column(name = "trigger_detected_at")
    private LocalDateTime detectedAt;

    /**
     * Disparador de un hecho publicado por otro contexto. El identificador del
     * evento es obligatorio salvo en acciones manuales.
     */
    public static MitigationTrigger fromEvent(String sourceEventId, TriggerKind kind,
                                              LocalDateTime detectedAt) {
        if (sourceEventId == null || sourceEventId.isBlank()) {
            throw new MitigationRuleException("Source event id is required for " + kind);
        }
        return new MitigationTrigger(sourceEventId, kind,
                detectedAt != null ? detectedAt : LocalDateTime.now());
    }

    /** Disparador de una decisión directa del SSO: no hay evento que lo respalde. */
    public static MitigationTrigger manual() {
        return new MitigationTrigger(null, TriggerKind.MANUAL_DECISION, LocalDateTime.now());
    }
}
