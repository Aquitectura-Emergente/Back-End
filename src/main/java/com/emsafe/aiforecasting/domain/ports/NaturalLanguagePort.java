package com.emsafe.aiforecasting.domain.ports;

import com.emsafe.aiforecasting.domain.model.NaturalLanguageRedaction;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;

/**
 * Redacción en lenguaje natural (IA generativa). Las implementaciones solo envían IDs y
 * métricas numéricas, nunca nombres ni datos personales. Puede lanzar excepción o devolver
 * texto vacío; quien la llama recurre a {@code FallbackMessagePolicy}.
 */
public interface NaturalLanguagePort {

    NaturalLanguageRedaction redact(PreventiveAssessment assessment);
}
