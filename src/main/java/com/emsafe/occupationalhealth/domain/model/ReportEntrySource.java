package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Una referencia de origen de una cifra del reporte (informe §5.6.1, regla 8).
 *
 * El informe reparte estas referencias en tres listas —exceedanceEventIds,
 * alertIds y actionIds— más el sessionId. Aquí van en una sola colección con un
 * tipo, porque la pregunta que importa es siempre la misma: ¿esta cifra tiene al
 * menos una referencia que la sustente? Eso es lo que mide QAS-07, y con tres
 * listas separadas habría que comprobarlo tres veces.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ReportEntrySource {

    public enum SourceType {
        /** La jornada de exposición de la que sale la dosis. */
        SESSION,
        /** El evento de excedencia que publicó Dosimetry. */
        EXCEEDANCE,
        /** La alerta cuyo acuse o escalamiento consta como evidencia. */
        ALERT,
        /** La medida de mitigación que se tomó. */
        ACTION
    }

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType type;

    @Column(name = "source_ref", nullable = false, length = 64)
    private String ref;

    public static ReportEntrySource of(SourceType type, String ref) {
        if (ref == null || ref.isBlank()) {
            throw new MitigationRuleException("A report source requires a reference");
        }
        return new ReportEntrySource(type, ref.trim());
    }

    public static ReportEntrySource session(String sessionId) {
        return of(SourceType.SESSION, sessionId);
    }

    public static ReportEntrySource action(Long actionId) {
        return of(SourceType.ACTION, String.valueOf(actionId));
    }

    public static ReportEntrySource alert(String alertId) {
        return of(SourceType.ALERT, alertId);
    }
}
