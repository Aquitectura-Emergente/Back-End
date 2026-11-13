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

import java.time.Instant;

/**
 * Entrada de auditoría append-only. Solo la crea el agregado {@link Device} y
 * se guarda en la misma transacción que el cambio que registra.
 */
@Entity
@Table(name = "device_audit_entries")
@Getter
public class DeviceAuditEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "device_id", nullable = false)
    private Long deviceId;
    @Column(name = "device_serial", length = 100)
    private String deviceSerial;
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 40)
    private AuditEntryType entryType;
    @Column(name = "field_name", length = 60)
    private String fieldName;
    @Column(name = "old_value", length = 255)
    private String oldValue;
    @Column(name = "new_value", length = 255)
    private String newValue;
    @Column(nullable = false, length = 150)
    private String actor;
    @Column(length = 500)
    private String details;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Transient
    private Device owner;

    protected DeviceAuditEntry() {
    }

    static DeviceAuditEntry of(Device owner, AuditEntryType type, String field, String oldValue,
                               String newValue, String actor, Instant at, String details) {
        DeviceAuditEntry e = new DeviceAuditEntry();
        e.owner = owner;
        e.deviceId = owner.getId();
        e.deviceSerial = owner.getSerialNumber();
        e.entryType = type;
        e.fieldName = field;
        e.oldValue = cut(oldValue, 255);
        e.newValue = cut(newValue, 255);
        e.actor = actor == null || actor.isBlank() ? "system" : cut(actor, 150);
        e.occurredAt = at;
        e.details = cut(details, 500);
        return e;
    }

    @PrePersist
    void bindOwner() {
        if (deviceId == null && owner != null) deviceId = owner.getId();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
