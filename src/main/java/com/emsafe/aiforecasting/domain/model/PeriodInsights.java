package com.emsafe.aiforecasting.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.invalid;
import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;
import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requireText;

/**
 * "Análisis con IA" de un periodo para el SSO: lectura consultiva del {@link AnalyticsSummary}.
 * No cambia criticidades, evaluaciones ni eventos. Lo genera el LLM o, si no está disponible o
 * responde algo inválido, reglas deterministas; {@code generatedBy} dice cuál.
 *
 * <p>Los límites de longitud y cantidad son los mismos para ambos orígenes: el adaptador del LLM
 * rechaza (y cae al fallback) una respuesta que no los cumpla.</p>
 */
public record PeriodInsights(Long organizationId,
                             LocalDate from,
                             LocalDate to,
                             String zone,
                             RedactionSource generatedBy,
                             String model,
                             Instant generatedAt,
                             String summary,
                             List<Finding> findings,
                             List<Recommendation> recommendations,
                             String dataQuality) {

    public static final int MAX_SUMMARY_LENGTH = 800;
    public static final int MAX_FINDINGS = 8;
    public static final int MAX_FINDING_LENGTH = 400;
    public static final int MAX_RECOMMENDATIONS = 8;
    public static final int MAX_ACTION_LENGTH = 300;
    public static final int MAX_REASON_LENGTH = 500;
    public static final int MAX_DATA_QUALITY_LENGTH = 600;

    public PeriodInsights {
        requirePresent(organizationId, "organizationId");
        requirePresent(from, "from");
        requirePresent(to, "to");
        requireText(zone, "zone");
        requirePresent(generatedBy, "generatedBy");
        requirePresent(generatedAt, "generatedAt");
        requireLength(summary, "summary", MAX_SUMMARY_LENGTH);
        requireLength(dataQuality, "dataQuality", MAX_DATA_QUALITY_LENGTH);
        requirePresent(findings, "findings");
        requirePresent(recommendations, "recommendations");
        if (findings.isEmpty() || findings.size() > MAX_FINDINGS) {
            throw invalid("findings must have between 1 and " + MAX_FINDINGS + " items");
        }
        if (recommendations.isEmpty() || recommendations.size() > MAX_RECOMMENDATIONS) {
            throw invalid("recommendations must have between 1 and " + MAX_RECOMMENDATIONS + " items");
        }
        findings = List.copyOf(findings);
        recommendations = List.copyOf(recommendations);
    }

    /** Hallazgo: un hecho de los datos con su nivel de atención. */
    public record Finding(String text, FindingLevel level) {
        public Finding {
            requireLength(text, "finding.text", MAX_FINDING_LENGTH);
            requirePresent(level, "finding.level");
        }
    }

    /** Acción preventiva sugerida al SSO, con los operarios y puestos a los que aplica. */
    public record Recommendation(String action,
                                 String reason,
                                 InsightPriority priority,
                                 List<Long> workerIds,
                                 List<PositionRef> positions) {
        public Recommendation {
            requireLength(action, "recommendation.action", MAX_ACTION_LENGTH);
            requireLength(reason, "recommendation.reason", MAX_REASON_LENGTH);
            requirePresent(priority, "recommendation.priority");
            workerIds = workerIds == null ? List.of() : List.copyOf(workerIds);
            positions = positions == null ? List.of() : List.copyOf(positions);
        }
    }

    /** {@code positionName} es {@code null} si Plant Configuration no lo conoce. */
    public record PositionRef(Long positionId, String positionName) {
        public PositionRef {
            requirePresent(positionId, "positionId");
        }
    }

    public enum FindingLevel { INFO, ATENCION, CRITICO }

    public enum InsightPriority { ALTA, MEDIA, BAJA }

    /** Recorta {@code text} a {@code max} caracteres (con "…"), p. ej. si los nombres lo alargaron. */
    public static String fit(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1).trim() + "…";
    }

    private static void requireLength(String value, String field, int max) {
        requireText(value, field);
        if (value.length() > max) {
            throw invalid(field + " must be at most " + max + " characters");
        }
    }
}
