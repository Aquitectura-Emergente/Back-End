package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.Decimals;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Prompt en español para redactar la evaluación. Solo lleva IDs, porcentajes, horas y el
 * tipo de recomendación: nunca nombres ni datos personales (este contexto no los conoce).
 */
final class GeminiPromptBuilder {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    static final String SYSTEM_INSTRUCTION = "Eres un asistente de seguridad ocupacional de EMSafe. "
            + "Redactas en español, en 2 a 4 frases, una explicación clara para el responsable de "
            + "seguridad (SSO). Es una PROYECCIÓN preventiva del índice magnético acumulado (uT·s), no "
            + "una dosis ni una excedencia confirmada. No inventes cifras ni datos que no estén en el "
            + "mensaje. La recomendación es una sugerencia: la decisión es del SSO.";

    private GeminiPromptBuilder() {
    }

    static String build(PreventiveAssessment a) {
        ExposureProjection p = a.getProjection();
        StringBuilder sb = new StringBuilder()
                .append("Evaluación preventiva del operario ").append(a.getWorkerId())
                .append(" (sesión ").append(a.getSessionId()).append(").\n")
                .append("- Criticidad: ").append(a.getCriticality()).append('\n')
                .append("- Índice consumido: ").append(Decimals.percent(p.consumedRatio())).append(" % del límite\n")
                .append("- Proyección al fin de turno (").append(TIME.format(p.shiftEnd())).append("): ")
                .append(Decimals.percent(p.projectedRatio())).append(" % del límite\n")
                .append("- Excedencia estimada: ")
                .append(p.estimatedExceedanceAt() == null ? "no se proyecta" : TIME.format(p.estimatedExceedanceAt()))
                .append('\n');
        RotationRecommendation r = a.getRecommendation().orElse(null);
        if (r == null) {
            sb.append("- Recomendación: ninguna\n");
        } else if (r.type() == RecommendationType.ROTATION) {
            sb.append("- Recomendación: ROTATION, intercambiar puesto ").append(r.sourcePositionId())
                    .append(" con el operario ").append(r.targetWorkerId())
                    .append(" (puesto ").append(r.targetPositionId()).append(")\n");
        } else {
            sb.append("- Recomendación: RESCHEDULE_TASK, reprogramar la tarea (sin candidato viable)\n");
        }
        return sb.append("Redacta la explicación.").toString();
    }
}
