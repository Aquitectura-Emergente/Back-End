package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.domain.model.ModelVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ForecastingPropertiesTest {

    @Test
    void defaultsMatchThePlan() {
        ForecastingProperties p = new ForecastingProperties();

        assertThat(p.getDefaultShiftHours()).isEqualTo(8);
        assertThat(p.defaultShift()).isEqualTo(Duration.ofHours(8));
        assertThat(p.getCriticalMarginRatio()).isEqualByComparingTo("0.10");
        assertThat(p.getModerateProjectedRatio()).isEqualByComparingTo("0.90");
        assertThat(p.getSafeCandidateRatio()).isEqualByComparingTo("0.90");
        assertThat(p.getModelId()).isEqualTo("LINEAR_RATE");
        assertThat(p.getModelVersion()).isEqualTo("1");
        assertThat(p.getLlm().isEnabled()).isFalse();
        assertThat(p.getLlm().getTimeoutMs()).isEqualTo(5000);
        assertThat(p.isSeedDemo()).isFalse();
        assertThat(p.thresholds().criticalMarginRatio()).isEqualByComparingTo("0.10");
    }

    @Test
    void bindsKebabCaseKeys() {
        Map<String, String> source = Map.of(
                "emsafe.ai-forecasting.default-shift-hours", "10",
                "emsafe.ai-forecasting.critical-margin-ratio", "0.2",
                "emsafe.ai-forecasting.model-id", "LINEAR_RATE",
                "emsafe.ai-forecasting.model-version", "2",
                "emsafe.ai-forecasting.llm.enabled", "true",
                "emsafe.ai-forecasting.llm.timeout-ms", "1500",
                "emsafe.ai-forecasting.seed-demo", "true");

        ForecastingProperties p = new Binder(new MapConfigurationPropertySource(source))
                .bind("emsafe.ai-forecasting", ForecastingProperties.class).get();

        assertThat(p.defaultShift()).isEqualTo(Duration.ofHours(10));
        assertThat(p.getCriticalMarginRatio()).isEqualByComparingTo("0.2");
        assertThat(p.getLlm().isEnabled()).isTrue();
        assertThat(p.getLlm().getTimeoutMs()).isEqualTo(1500);
        assertThat(p.isSeedDemo()).isTrue();
        assertThat(new PropertiesModelVersionProvider(p).current()).isEqualTo(new ModelVersion("LINEAR_RATE", "2"));
    }

    @Test
    void invalidThresholdsFailFast() {
        ForecastingProperties p = new ForecastingProperties();
        p.setSafeCandidateRatio(new java.math.BigDecimal("1.5"));

        assertThatThrownBy(p::thresholds)
                .isInstanceOf(com.emsafe.aiforecasting.domain.model.ForecastingRuleException.class);
    }

    @Test
    void nonPositiveShiftHoursFailFast() {
        ForecastingProperties p = new ForecastingProperties();
        p.setDefaultShiftHours(0);

        assertThatThrownBy(p::defaultShift).isInstanceOf(IllegalStateException.class);
    }
}
