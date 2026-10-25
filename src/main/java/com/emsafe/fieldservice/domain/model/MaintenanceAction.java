package com.emsafe.fieldservice.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A maintenance action performed against a device during a Maintenance work order.
 */
@Entity
@Table(name = "maintenance_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaintenanceAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Device targeted by the action (denormalized id + name for display). */
    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "device_name", length = 150)
    private String deviceName;

    @Column(nullable = false, length = 200)
    private String action;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;
}
