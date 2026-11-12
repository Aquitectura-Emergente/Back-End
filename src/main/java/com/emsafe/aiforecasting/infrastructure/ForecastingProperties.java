package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.domain.model.ForecastingThresholds;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/** Configuración {@code emsafe.ai-forecasting.*}. Los valores por defecto son los del plan. */
@Getter
@Setter
@ConfigurationProperties("emsafe.ai-forecasting")
public class ForecastingProperties {

    /** Duración del turno cuando el snapshot no trae plannedShiftEnd. */
    private int defaultShiftHours = 8;
    private BigDecimal criticalMarginRatio = new BigDecimal("0.10");
    private BigDecimal moderateProjectedRatio = new BigDecimal("0.90");
    private BigDecimal safeCandidateRatio = new BigDecimal("0.90");
    private String modelId = "LINEAR_RATE";
    private String modelVersion = "1";
    /** Crea snapshots de demostración al arrancar. */
    private boolean seedDemo = false;
    private Llm llm = new Llm();

    @Getter
    @Setter
    public static class Llm {
        /** Si es false no se llama a Gemini y se usa siempre la plantilla. */
        private boolean enabled = false;
        private int timeoutMs = 5000;
    }

    /** Valida y construye los umbrales; un valor fuera de (0, 1] impide arrancar. */
    public ForecastingThresholds thresholds() {
        return new ForecastingThresholds(criticalMarginRatio, moderateProjectedRatio, safeCandidateRatio);
    }

    public Duration defaultShift() {
        if (defaultShiftHours <= 0) {
            throw new IllegalStateException("emsafe.ai-forecasting.default-shift-hours must be > 0");
        }
        return Duration.ofHours(defaultShiftHours);
    }
}
