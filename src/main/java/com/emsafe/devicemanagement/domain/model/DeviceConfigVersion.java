package com.emsafe.devicemanagement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** Historial append-only de las versiones de configuración de un dispositivo. */
@Entity
@Table(name = "device_configs")
@Getter
public class DeviceConfigVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "device_id", nullable = false)
    private Long deviceId;
    @Column(name = "config_version", nullable = false)
    private Integer configVersion;
    @Column(name = "alert_threshold_ut", nullable = false, precision = 10, scale = 3)
    private BigDecimal alertThresholdUt;
    @Enumerated(EnumType.STRING)
    @Column(name = "threshold_defined_by", nullable = false, length = 20)
    private ConfigDefinedBy definedBy;
    @Column(name = "sampling_interval_ms", nullable = false)
    private Long samplingIntervalMs;
    @Column(name = "inactivity_timeout_s", nullable = false)
    private Long inactivityTimeoutS;
    @Column(name = "changed_by", nullable = false, length = 150)
    private String changedBy;
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;
    @Transient
    private Device owner;

    protected DeviceConfigVersion() {
    }

    static DeviceConfigVersion of(Device owner, DeviceConfig config, String changedBy) {
        DeviceConfigVersion v = new DeviceConfigVersion();
        v.owner = owner;
        v.deviceId = owner.getId();
        v.configVersion = config.version();
        v.alertThresholdUt = config.alertThreshold().microtesla();
        v.definedBy = config.definedBy();
        v.samplingIntervalMs = config.samplingInterval().millis();
        v.inactivityTimeoutS = config.inactivityTimeout().seconds();
        v.changedBy = changedBy;
        v.changedAt = config.updatedAt();
        return v;
    }

    /** El dispositivo nuevo recibe su id al guardarse, antes que su historial. */
    @PrePersist
    void bindOwner() {
        if (deviceId == null && owner != null) deviceId = owner.getId();
    }
}
