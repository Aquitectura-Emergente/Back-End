package com.emsafe.devicemanagement.domain.model;

import com.emsafe.devicemanagement.domain.events.EdgeConnectivityChanged;
import com.emsafe.shared.exception.BadRequestException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Nodo Edge conocido por Device Management, de planta o de hogar. Los de
 * planta además están declarados en Plant Configuration.
 */
@Entity
@Table(name = "edge_nodes")
@Getter
public class EdgeNode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "edge_node_id", nullable = false, length = 100, unique = true)
    private String edgeNodeId;
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EdgeSegment segment;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ConnectivityStatus connectivity;
    @Column(name = "connectivity_changed_at")
    private Instant connectivityChangedAt;
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Transient
    @Getter(AccessLevel.NONE)
    private List<EdgeConnectivityChanged> pendingEvents;

    protected EdgeNode() {
    }

    public static EdgeNode register(String edgeNodeId, Long organizationId, EdgeSegment segment, Instant now) {
        if (edgeNodeId == null || edgeNodeId.isBlank() || edgeNodeId.length() > 100) {
            throw new BadRequestException("Edge node id is required (max 100 characters)");
        }
        EdgeNode e = new EdgeNode();
        e.edgeNodeId = edgeNodeId.trim();
        e.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        e.segment = Objects.requireNonNull(segment, "segment");
        e.connectivity = ConnectivityStatus.OFFLINE;
        e.createdAt = now;
        return e;
    }

    public boolean belongsTo(Long organization) {
        return organizationId.equals(organization);
    }

    /** Ignora estados más viejos que el vigente; el Last Will llega con la hora de recepción. */
    public boolean changeConnectivity(ConnectivityStatus status, Instant changedAt) {
        if (connectivityChangedAt != null && changedAt.isBefore(connectivityChangedAt)) return false;
        if (status == connectivity && connectivityChangedAt != null) return false;
        ConnectivityStatus previous = connectivity;
        connectivity = status;
        connectivityChangedAt = changedAt;
        if (status == ConnectivityStatus.ONLINE) markSeen(changedAt);
        if (status != previous) {
            pending().add(new EdgeConnectivityChanged(edgeNodeId, organizationId, segment, status, changedAt));
            return true;
        }
        return false;
    }

    public void markSeen(Instant at) {
        if (at != null && (lastSeenAt == null || at.isAfter(lastSeenAt))) lastSeenAt = at;
    }

    public List<EdgeConnectivityChanged> pullEvents() {
        List<EdgeConnectivityChanged> copy = List.copyOf(pending());
        pending().clear();
        return copy;
    }

    private List<EdgeConnectivityChanged> pending() {
        if (pendingEvents == null) pendingEvents = new ArrayList<>();
        return pendingEvents;
    }
}
