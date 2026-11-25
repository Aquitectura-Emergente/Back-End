package com.emsafe.aiforecasting.interfaces.rest.dto;

import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Representación REST del "Análisis con IA" ({@code POST /analytics/organizations/{id}/insights}). */
public final class InsightsResources {

    public static final String DISCLAIMER_LLM = "Análisis generado por IA (Gemini). Las cifras provienen del "
            + "motor de pronóstico y deben ser validadas por el SSO.";
    public static final String DISCLAIMER_FALLBACK = "Análisis generado con reglas automáticas (IA no disponible). "
            + "Las cifras provienen del motor de pronóstico y deben ser validadas por el SSO.";

    /** El aviso depende del origen: no se dice "IA" si el análisis lo generaron las reglas. */
    public static String disclaimer(RedactionSource generatedBy) {
        return generatedBy == RedactionSource.LLM ? DISCLAIMER_LLM : DISCLAIMER_FALLBACK;
    }

    private InsightsResources() {
    }

    @Schema(description = "Análisis consultivo del periodo para el SSO. No cambia criticidades, evaluaciones ni "
            + "eventos.")
    public record Insights(
            Long organizationId,
            @Schema(allowableValues = {"LLM", "FALLBACK"},
                    description = "LLM: Gemini; FALLBACK: reglas deterministas a partir del resumen") String generatedBy,
            @Schema(description = "Modelo de Gemini; solo con generatedBy = LLM", example = "gemini-2.5-flash")
            String model,
            Instant generatedAt,
            Period period,
            @Schema(description = "Siempre true: es una sugerencia, la decisión es del SSO") boolean isAdvisory,
            @Schema(description = "Depende de generatedBy", example = DISCLAIMER_FALLBACK) String disclaimer,
            @Schema(description = "2 o 3 frases; los operarios, por su nombre (o \"operario #id\")") String resumen,
            List<Hallazgo> hallazgos,
            List<Recomendacion> recomendaciones,
            @Schema(description = "Cobertura incompleta o pocos datos") String calidadDatos) {

        public static Insights from(PeriodInsights i) {
            return new Insights(i.organizationId(), i.generatedBy().name(), i.model(), i.generatedAt(),
                    new Period(i.from(), i.to(), i.zone()), true, InsightsResources.disclaimer(i.generatedBy()),
                    i.summary(),
                    i.findings().stream().map(f -> new Hallazgo(f.text(), f.level().name())).toList(),
                    i.recommendations().stream().map(r -> new Recomendacion(r.action(), r.reason(),
                            r.priority().name(), r.workerIds(),
                            r.positions().stream().map(p -> new Puesto(p.positionId(), p.positionName())).toList()))
                            .toList(),
                    i.dataQuality());
        }
    }

    public record Period(
            @Schema(description = "Primer día (inclusivo), en la zona pedida") LocalDate from,
            @Schema(description = "Último día (inclusivo), en la zona pedida") LocalDate to,
            @Schema(example = "America/Lima") String zone) {
    }

    public record Hallazgo(String texto, @Schema(allowableValues = {"INFO", "ATENCION", "CRITICO"}) String nivel) {
    }

    public record Recomendacion(
            String accion,
            String motivo,
            @Schema(allowableValues = {"ALTA", "MEDIA", "BAJA"}) String prioridad,
            @Schema(description = "workerId de los operarios afectados") List<Long> operariosAfectados,
            List<Puesto> puestosAfectados) {
    }

    public record Puesto(Long positionId,
                         @Schema(description = "null si Plant Configuration no lo conoce") String positionName) {
    }
}
