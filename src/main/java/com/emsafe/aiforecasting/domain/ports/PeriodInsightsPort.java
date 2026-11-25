package com.emsafe.aiforecasting.domain.ports;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.PeriodInsights;
import com.emsafe.aiforecasting.domain.model.WorkerNames;

/**
 * "Análisis con IA" del periodo (IA generativa). Las implementaciones solo envían datos agregados
 * del {@link AnalyticsSummary}, con seudónimos en lugar de IDs: ningún nombre, email ni ID real
 * sale del backend. Si el LLM no está disponible o responde algo inválido, devuelven el análisis
 * determinista de {@code InsightsFallbackPolicy}. Es consultivo: no modifica nada.
 *
 * <p>{@code names} solo sirve para escribir los textos finales (nombre del operario o
 * "operario #id"); nunca se envía al LLM.</p>
 */
public interface PeriodInsightsPort {

    PeriodInsights analyze(AnalyticsSummary summary, WorkerNames names);
}
