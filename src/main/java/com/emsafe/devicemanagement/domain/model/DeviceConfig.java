package com.emsafe.devicemanagement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Configuración vigente del dispositivo. Inmutable: cada cambio crea una
 * versión nueva y el agregado guarda la anterior en el historial.
 */
@Embeddable
public class DeviceConfig {
    @Column(name = "config_version")
    private Integer version;
    @Column(name = "alert_threshold_ut", precision = 10, scale = 3)
    private BigDecimal alertThresholdUt;
    @Enumerated(EnumType.STRING)
    @Column(name = "threshold_defined_by", length = 20)
    private ConfigDefinedBy definedBy;
    @Column(name = "sampling_interval_ms")
    private Long samplingIntervalMs;
    @Column(name = "inactivity_timeout_s")
    private Long inactivityTimeoutS;
    @Column(name = "config_updated_at")
    private Instant updatedAt;

    protected DeviceConfig() {
    }

    private DeviceConfig(int version, AlertThreshold threshold, ConfigDefinedBy definedBy,
                         SamplingInterval sampling, InactivityTimeout inactivity, Instant updatedAt) {
        this.version = version;
        this.alertThresholdUt = threshold.microtesla();
        this.definedBy = definedBy;
        this.samplingIntervalMs = sampling.millis();
        this.inactivityTimeoutS = inactivity.seconds();
        this.updatedAt = updatedAt;
    }

    public static DeviceConfig initial(Instant at) {
        return new DeviceConfig(1, AlertThreshold.DEFAULT, ConfigDefinedBy.SYSTEM_DEFAULT,
                SamplingInterval.DEFAULT, InactivityTimeout.DEFAULT, at);
    }

    DeviceConfig withThreshold(AlertThreshold threshold, ConfigDefinedBy by, Instant at) {
        return new DeviceConfig(version() + 1, threshold, by, samplingInterval(), inactivityTimeout(), at);
    }

    DeviceConfig withOperational(SamplingInterval sampling, InactivityTimeout inactivity, Instant at) {
        return new DeviceConfig(version() + 1, alertThreshold(), definedBy(), sampling, inactivity, at);
    }

    public int version() {
        return version == null ? 1 : version;
    }

    public AlertThreshold alertThreshold() {
        return alertThresholdUt == null ? AlertThreshold.DEFAULT : new AlertThreshold(alertThresholdUt);
    }

    public ConfigDefinedBy definedBy() {
        return definedBy == null ? ConfigDefinedBy.SYSTEM_DEFAULT : definedBy;
    }

    public SamplingInterval samplingInterval() {
        return samplingIntervalMs == null ? SamplingInterval.DEFAULT : new SamplingInterval(samplingIntervalMs);
    }

    public InactivityTimeout inactivityTimeout() {
        return inactivityTimeoutS == null ? InactivityTimeout.DEFAULT : new InactivityTimeout(inactivityTimeoutS);
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
