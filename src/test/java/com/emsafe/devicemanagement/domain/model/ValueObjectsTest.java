package com.emsafe.devicemanagement.domain.model;

import com.emsafe.shared.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueObjectsTest {

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, 790, 800, Double.NaN})
    void anAlertThresholdTheEdgeWouldRejectIsRejected(double value) {
        assertThatThrownBy(() -> AlertThreshold.of(value)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anAlertThresholdAlwaysHasDecimals() {
        assertThat(AlertThreshold.of(300).microtesla().toPlainString()).isEqualTo("300.0");
        assertThat(new AlertThreshold(new BigDecimal("250.500")).microtesla().toPlainString()).isEqualTo("250.5");
        assertThat(AlertThreshold.of(789.9).microtesla()).isEqualByComparingTo("789.9");
    }

    @Test
    void readingIdentityIsAValue() {
        Instant at = Instant.parse("2026-11-12T15:04:04Z");
        assertThat(new ReadingIdentity(DeviceSerial.of(" EMSafe-DOS-01 "), 1234, at))
                .isEqualTo(new ReadingIdentity(DeviceSerial.of("EMSafe-DOS-01"), 1234, at));
        assertThatThrownBy(() -> new ReadingIdentity(DeviceSerial.of("D"), -1, at))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void fluxDensityIsFiniteAndNotNegative() {
        assertThat(MagneticFluxDensity.ofMicrotesla(219.5).microtesla()).isEqualByComparingTo("219.5");
        assertThatThrownBy(() -> MagneticFluxDensity.ofMicrotesla(-0.1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> MagneticFluxDensity.ofMicrotesla(Double.NaN)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void saturatedIsALowerBound() {
        assertThat(ReadingQuality.SATURATED.isLowerBound()).isTrue();
        assertThat(ReadingQuality.VALID.isLowerBound()).isFalse();
    }

    @Test
    void organizationCodesAreSlugs() {
        assertThat(new OrganizationCode("org-demo").value()).isEqualTo("org-demo");
        assertThat(new OrganizationCode("home-demo").value()).isEqualTo("home-demo");
        assertThatThrownBy(() -> new OrganizationCode("Org Demo")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new OrganizationCode("7")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void serialsAndIntervalsAreValidated() {
        assertThatThrownBy(() -> DeviceSerial.of("  ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new SamplingInterval(50)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> new InactivityTimeout(5)).isInstanceOf(BadRequestException.class);
        assertThat(SamplingInterval.DEFAULT.duration().toMillis()).isEqualTo(1000);
    }

    @Test
    void edgeNodesIgnoreOlderStates() {
        Instant t = Instant.parse("2026-11-12T15:00:00Z");
        EdgeNode edge = EdgeNode.register("EDGE-PLANTA-01", 7L, EdgeSegment.PLANT, t);
        assertThat(edge.changeConnectivity(ConnectivityStatus.ONLINE, t)).isTrue();
        assertThat(edge.changeConnectivity(ConnectivityStatus.OFFLINE, t.minusSeconds(1))).isFalse();
        assertThat(edge.changeConnectivity(ConnectivityStatus.OFFLINE, t.plusSeconds(1))).isTrue();
        assertThat(edge.pullEvents()).hasSize(2);
        assertThat(edge.belongsTo(7L)).isTrue();
    }
}
