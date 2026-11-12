package com.emsafe.aiforecasting.domain.services;

import com.emsafe.aiforecasting.domain.model.Decimals;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.RecommendationType;
import com.emsafe.aiforecasting.domain.model.RedactionSource;
import com.emsafe.aiforecasting.domain.model.RotationRecommendation;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;

/**
 * Redacción con plantilla fija y cifras numéricas. Se usa cuando el LLM está deshabilitado,
 * falla, excede el timeout o devuelve texto vacío: la evaluación nunca falla por el LLM.
 */
public class FallbackMessagePolicy {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    public NaturalLanguageRedaction redact(PreventiveAssessment assessment, Instant generatedAt) {
        requirePresent(assessment, "assessment");
        requirePresent(generatedAt, "generatedAt");
        if (assessment.getCriticality() == null) {
            throw ForecastingRuleException.invalid("cannot redact an assessment before it is assessed");
        }
        ExposureProjection p = assessment.getProjection();

        StringBuilder text = new StringBuilder()
                .append("Operario ").append(assessment.getWorkerId())
                .append(" (sesión ").append(assessment.getSessionId()).append("): criticidad ")
                .append(assessment.getCriticality()).append(". ")
                .append("Índice consumido ").append(Decimals.percent(p.consumedRatio())).append(" % del límite; ")
                .append("proyección al fin de turno (").append(TIME.format(p.shiftEnd())).append(") ")
                .append(Decimals.percent(p.projectedRatio())).append(" %. ");

        if (p.estimatedExceedanceAt() != null) {
            text.append("Excedencia estimada: ").append(DATE_TIME.format(p.estimatedExceedanceAt())).append(". ");
        } else {
            text.append("Turno sin excedencia proyectada. ");
        }
        assessment.getRecommendation().ifPresent(r -> text.append(describe(r)));
        return new NaturalLanguageRedaction(text.toString().trim(), RedactionSource.FALLBACK, generatedAt);
    }

    private static String describe(RotationRecommendation r) {
        String benefit = Decimals.scaled(r.estimatedBenefitIndex()).toPlainString();
        if (r.type() == RecommendationType.ROTATION) {
            return "Recomendación: intercambiar puesto con el operario " + r.targetWorkerId()
                    + " (puesto " + r.sourcePositionId() + " → " + r.targetPositionId()
                    + "); beneficio estimado " + benefit + " uT·s.";
        }
        return "Recomendación: reprogramar la tarea (sin candidato viable); exposición evitable estimada "
                + benefit + " uT·s.";
    }
}
