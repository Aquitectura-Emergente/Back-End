package com.emsafe.aiforecasting.infrastructure.llm;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.PositionStats;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.ProjectedCritical;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.Totals;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WeekStats;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary.WorkerStats;
import com.emsafe.aiforecasting.domain.model.Decimals;
import com.emsafe.aiforecasting.domain.model.PeriodInsights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Prompt del "Análisis con IA" del periodo. Solo lleva datos agregados del resumen: conteos,
 * porcentajes, índices en µT·s, semanas ISO y horas. Operarios y puestos van con seudónimos de
 * {@link InsightsPseudonyms}; nunca IDs de organización, operario, puesto, sesión o evaluación,
 * nombres ni emails.
 */
final class GeminiInsightsPromptBuilder {

    static final String SYSTEM_INSTRUCTION = """
            Eres un asistente de seguridad ocupacional de EMSafe. Analizas el resumen de exposición a \
            campos magnéticos de un periodo (índice magnético acumulado en µT·s y su porcentaje del límite \
            por jornada) y escribes en español para el responsable de seguridad y salud ocupacional (SSO).
            Reglas:
            1. Usa SOLO los datos del mensaje. No inventes cifras, fechas, operarios ni puestos, y no \
            calcules cifras nuevas: cita las que se te dan.
            2. Nombra a cada operario y puesto solo por su seudónimo completo y en singular, por ejemplo \
            "Operario A" o "Puesto B". No agrupes seudónimos ("operarios A y B"): escribe "Operario A y \
            Operario B". No uses otros identificadores.
            3. Las proyecciones de jornadas abiertas son estimaciones preventivas, no excedencias confirmadas.
            4. Sugiere acciones preventivas concretas y justificadas por los datos: rotación, pausas, revisar \
            el puesto o el blindaje, reprogramar tareas, capacitación.
            5. El análisis es consultivo: la decisión es del SSO. No digas que ya se tomó ninguna acción.
            6. Copia las cifras exactamente como se dan, con su separador de miles (por ejemplo 396,819 µT·s o 12,117 %%), y escribe la unidad como µT·s.
            7. Responde ÚNICAMENTE con un objeto JSON con esta forma:
            {"resumen": "2 o 3 frases",
             "hallazgos": [{"texto": "...", "nivel": "INFO|ATENCION|CRITICO"}],
             "recomendaciones": [{"accion": "...", "motivo": "...", "prioridad": "ALTA|MEDIA|BAJA",
                                  "operariosAfectados": ["Operario A"], "puestosAfectados": ["Puesto A"]}],
             "calidadDatos": "si hay cobertura incompleta o pocos datos, dilo; si no, indica que los datos \
            son suficientes"}
            Entre 1 y %d hallazgos y entre 1 y %d recomendaciones, ordenados de mayor a menor importancia. \
            Máximo %d caracteres en resumen, %d por hallazgo, %d por acción, %d por motivo y %d en calidadDatos.\
            """.formatted(PeriodInsights.MAX_FINDINGS, PeriodInsights.MAX_RECOMMENDATIONS,
            PeriodInsights.MAX_SUMMARY_LENGTH, PeriodInsights.MAX_FINDING_LENGTH, PeriodInsights.MAX_ACTION_LENGTH,
            PeriodInsights.MAX_REASON_LENGTH, PeriodInsights.MAX_DATA_QUALITY_LENGTH);

    /** {@code responseSchema} de Gemini (subconjunto OpenAPI) para forzar la estructura. */
    static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "resumen", Map.of("type", "STRING"),
                    "hallazgos", Map.of("type", "ARRAY", "items", Map.of(
                            "type", "OBJECT",
                            "properties", Map.of(
                                    "texto", Map.of("type", "STRING"),
                                    "nivel", Map.of("type", "STRING", "enum", List.of("INFO", "ATENCION", "CRITICO"))),
                            "required", List.of("texto", "nivel"))),
                    "recomendaciones", Map.of("type", "ARRAY", "items", Map.of(
                            "type", "OBJECT",
                            "properties", Map.of(
                                    "accion", Map.of("type", "STRING"),
                                    "motivo", Map.of("type", "STRING"),
                                    "prioridad", Map.of("type", "STRING", "enum", List.of("ALTA", "MEDIA", "BAJA")),
                                    "operariosAfectados", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                                    "puestosAfectados", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                            "required", List.of("accion", "motivo", "prioridad"))),
                    "calidadDatos", Map.of("type", "STRING")),
            "required", List.of("resumen", "hallazgos", "recomendaciones", "calidadDatos"));

    private GeminiInsightsPromptBuilder() {
    }

    static String build(AnalyticsSummary s, InsightsPseudonyms names) {
        Totals t = s.totals();
        ZoneId zone = ZoneId.of(s.zone());
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        StringBuilder sb = new StringBuilder()
                .append("Periodo: del ").append(s.from()).append(" al ").append(s.to()).append(" (")
                .append(ChronoUnit.DAYS.between(s.from(), s.to()) + 1).append(" días, zona ").append(s.zone())
                .append(").\n\n")
                .append("Totales de jornadas cerradas (dato medido):\n")
                .append("- Jornadas cerradas: ").append(t.closedSessions()).append("; operarios: ").append(t.workers())
                .append('\n')
                .append("- Sobre la advertencia: ").append(t.sessionsAboveWarning()).append('\n')
                .append("- Cerca del límite (índice final ≥ ").append(Decimals.percent(t.nearLimitRatio()))
                .append(" % del límite, incluye las que lo superaron): ").append(t.sessionsNearLimit())
                .append(" (").append(pct(t.sessionsNearLimitPct())).append(" % de las jornadas)\n")
                .append("- Superaron el límite: ").append(t.sessionsExceeded()).append(" (")
                .append(pct(t.sessionsExceededPct())).append(" % de las jornadas)\n")
                .append("- Con cobertura incompleta del dosímetro: ").append(t.incompleteCoverageSessions())
                .append("\n\n");

        sb.append("Por operario (jornadas; índice final promedio; máximo; máximo en % del límite; ")
                .append("sobre la advertencia; cerca del límite; superaron el límite):\n");
        if (s.perWorker().isEmpty()) sb.append("- (sin datos)\n");
        for (WorkerStats w : s.perWorker()) {
            sb.append("- ").append(names.worker(w.workerId())).append(": ").append(w.sessions()).append("; ")
                    .append(index(w.avgFinalIndex())).append(" µT·s; ").append(index(w.maxFinalIndex()))
                    .append(" µT·s; ").append(ratio(w.maxRatio())).append("; ").append(w.sessionsAboveWarning())
                    .append("; ").append(w.sessionsNearLimit()).append("; ").append(w.sessionsExceeded()).append('\n');
        }

        sb.append("\nTendencia por semana ISO (jornadas; índice final promedio; máximo; cerca del límite; ")
                .append("superaron el límite):\n");
        if (s.weeklyTrend().isEmpty()) sb.append("- (sin datos)\n");
        for (WeekStats w : s.weeklyTrend()) {
            sb.append("- ").append(w.isoWeek()).append(": ").append(w.sessions()).append("; ")
                    .append(index(w.avgFinalIndex())).append("; ").append(index(w.maxFinalIndex())).append("; ")
                    .append(w.sessionsNearLimit()).append("; ").append(w.sessionsExceeded()).append('\n');
        }

        sb.append("\nPuestos, de mayor a menor índice final promedio (jornadas; promedio; máximo):\n");
        if (s.topPositions().isEmpty()) sb.append("- (sin datos)\n");
        for (PositionStats p : s.topPositions()) {
            sb.append("- ").append(names.position(p.positionId())).append(": ").append(p.sessions()).append("; ")
                    .append(index(p.avgFinalIndex())).append(" µT·s; ").append(index(p.maxFinalIndex()))
                    .append(" µT·s\n");
        }

        sb.append("\nProyecciones actuales de jornadas abiertas con criticidad HIGH o CRITICAL (estimación ")
                .append("preventiva, no dato medido):\n");
        if (s.projectedCriticalWorkers().isEmpty()) sb.append("- (ninguna)\n");
        for (ProjectedCritical p : s.projectedCriticalWorkers()) {
            sb.append("- ").append(names.worker(p.workerId())).append(": ").append(p.criticality())
                    .append("; índice actual ").append(ratio(current(p))).append(" del límite; proyección al fin ")
                    .append("del turno ").append(ratio(p.projectedRatio())).append(" del límite; excedencia estimada ")
                    .append(p.estimatedExceedanceAt() == null ? "no se proyecta" : time.format(p.estimatedExceedanceAt()))
                    .append("; recomendación del motor: ");
            if (p.recommendationType() == null) {
                sb.append("ninguna");
            } else {
                sb.append(p.recommendationType());
                if (p.targetWorkerId() != null) {
                    sb.append(" con ").append(names.worker(p.targetWorkerId()));
                }
            }
            sb.append('\n');
        }

        sb.append("\nRecomendaciones emitidas por el motor en el periodo: rotación ")
                .append(s.recommendations().rotation()).append(", reprogramar tarea ")
                .append(s.recommendations().rescheduleTask()).append(".\n\n")
                .append("Seudónimos válidos de operarios: ")
                .append(names.workerLetters().isEmpty() ? "(ninguno)" : names.workerLetters()).append(".\n")
                .append("Seudónimos válidos de puestos: ")
                .append(names.positionLetters().isEmpty() ? "(ninguno)" : names.positionLetters()).append(".\n\n")
                .append("Escribe el análisis del periodo para el SSO.");
        return sb.toString();
    }

    private static BigDecimal current(ProjectedCritical p) {
        if (p.currentIndex() == null || p.limitIndex() == null || p.limitIndex().signum() == 0) return null;
        return Decimals.divide(p.currentIndex(), p.limitIndex());
    }

    /** Índice redondeado y con separador de miles, como lo muestra el portal: 396,819. */
    private static String index(BigDecimal value) {
        return value == null ? "s/d" : grouped(value.setScale(0, RoundingMode.HALF_UP), "#,##0");
    }

    /** Cociente como porcentaje legible: 12,117 % o 90.8 %. */
    private static String ratio(BigDecimal value) {
        if (value == null) return "s/d";
        BigDecimal percent = new BigDecimal(Decimals.percent(value));
        return grouped(percent, percent.abs().compareTo(BigDecimal.valueOf(100)) >= 0 ? "#,##0" : "#,##0.#") + " %";
    }

    private static String grouped(BigDecimal value, String pattern) {
        java.text.DecimalFormat format = new java.text.DecimalFormat(pattern,
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    private static String pct(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
