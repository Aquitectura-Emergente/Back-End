package com.emsafe.devicemanagement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lectura cruda tal como llegó del Edge, con su arranque derivado y el
 * destino que tuvo. Nunca se descarta, tampoco si está saturada.
 */
@Entity
@Table(name = "telemetry_readings", uniqueConstraints = @UniqueConstraint(
        name = "uq_telemetry_identity", columnNames = {"device_serial", "sequence_number", "captured_at"}))
@Getter
public class TelemetryReading {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "device_id", nullable = false)
    private Long deviceId;
    @Column(name = "device_serial", nullable = false, length = 100)
    private String deviceSerial;
    @Column(name = "organization_id")
    private Long organizationId;
    @Column(name = "edge_node_id", nullable = false, length = 100)
    private String edgeNodeId;
    @Column(name = "message_id", nullable = false, length = 64)
    private String messageId;
    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "captured_at_source", nullable = false, length = 20)
    private CapturedAtSource capturedAtSource;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(name = "flux_ut", nullable = false, precision = 12, scale = 3)
    private BigDecimal fluxUt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SyncOrigin origin;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReadingQuality quality;
    @Column(name = "boot_id", nullable = false, length = 64)
    private String bootId;
    @Enumerated(EnumType.STRING)
    @Column(name = "reported_type", nullable = false, length = 30)
    private DeviceType reportedType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReadingRouting routing;
    @Column(name = "routing_ref", length = 64)
    private String routingRef;
    @Column(name = "routing_detail", length = 255)
    private String routingDetail;
    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected TelemetryReading() {
    }

    public static TelemetryReading capture(Long deviceId, ReadingIdentity identity, Long organizationId,
                                           String edgeNodeId, String messageId, CapturedAtSource source,
                                           Instant receivedAt, MagneticFluxDensity flux, SyncOrigin origin,
                                           ReadingQuality quality, BootId bootId, DeviceType reportedType,
                                           Instant now) {
        TelemetryReading r = new TelemetryReading();
        r.deviceId = deviceId;
        r.deviceSerial = identity.device().value();
        r.sequenceNumber = identity.sequence();
        r.capturedAt = identity.capturedAt();
        r.organizationId = organizationId;
        r.edgeNodeId = edgeNodeId;
        r.messageId = messageId;
        r.capturedAtSource = source;
        r.receivedAt = receivedAt;
        r.fluxUt = flux.microtesla();
        r.origin = origin;
        r.quality = quality;
        r.bootId = bootId.value();
        r.reportedType = reportedType;
        r.routing = ReadingRouting.UNASSIGNED;
        r.ingestedAt = now;
        return r;
    }

    public ReadingIdentity identity() {
        return new ReadingIdentity(DeviceSerial.of(deviceSerial), sequenceNumber, capturedAt);
    }

    public BootId boot() {
        return new BootId(bootId);
    }

    public boolean isSaturated() {
        return quality != null && quality.isLowerBound();
    }

    public void assignToSession(String sessionId, String readingRef) {
        route(ReadingRouting.ASSIGNED_TO_SESSION, sessionId, readingRef);
    }

    public void leaveUnassigned(String reason) {
        route(ReadingRouting.UNASSIGNED, null, reason);
    }

    public void recordAtHome(String readingRef) {
        route(ReadingRouting.HOME_RECORDED, readingRef, null);
    }

    public void rejectedByTarget(String reason) {
        route(ReadingRouting.REJECTED_BY_TARGET, null, reason);
    }

    private void route(ReadingRouting target, String ref, String detail) {
        routing = target;
        routingRef = ref;
        routingDetail = detail == null || detail.length() <= 255 ? detail : detail.substring(0, 255);
    }
}
