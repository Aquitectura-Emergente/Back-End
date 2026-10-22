package com.emsafe.fieldservice.domain.model;

import com.emsafe.iam.domain.model.AppUser;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "work_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 20)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkOrderType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private WorkOrderStatus status = WorkOrderStatus.PENDING;

    @Column(nullable = false, length = 150)
    private String client;

    // Client reference (nullable — legacy orders only carry the client name string).
    // Used to resolve the client's devices in Maintenance / Collection flows.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_user_id")
    private AppUser clientUser;

    @Column(length = 200)
    private String location;

    @Column(length = 100)
    private String city;

    private LocalDate scheduledDate;

    @Column(length = 20)
    private String scheduledTime;

    // Technician reference (nullable — may not be assigned yet)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technician_id")
    private AppUser technician;

    // Denormalized for quick display without JOIN
    @Column(length = 100)
    private String technicianName;

    @Column(length = 5)
    private String technicianInitials;

    @Column(length = 30)
    private String priority;

    @Column(length = 100)
    private String contactName;

    @Column(length = 100)
    private String contactRole;

    @Column(length = 30)
    private String contactPhone;

    @Column(length = 150)
    private String contactEmail;

    @Column(columnDefinition = "TEXT")
    private String accessInstructions;

    private Integer expectedSensors;

    @Column(length = 50)
    private String assetId;

    @Column(columnDefinition = "TEXT")
    private String technicianNotes;

    // Timestamp set when the work order is marked Completed by the technician.
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // Reason captured when an order is Cancelled / Deleted by the admin.
    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    @ElementCollection
    @CollectionTable(name = "work_order_tools",
            joinColumns = @JoinColumn(name = "work_order_id"))
    @Column(name = "tool")
    @BatchSize(size = 30)
    @Builder.Default
    private List<String> requiredTools = new ArrayList<>();

    @OneToMany(mappedBy = "workOrder",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @BatchSize(size = 30)
    @Builder.Default
    private List<Sensor> sensors = new ArrayList<>();

    @OneToMany(mappedBy = "workOrder",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 30)
    @Builder.Default
    private List<ActivityLogEntry> activityLog = new ArrayList<>();

    @OneToMany(mappedBy = "workOrder",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 30)
    @Builder.Default
    private List<WorkOrderEvidence> evidence = new ArrayList<>();

    @OneToMany(mappedBy = "workOrder",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    @OrderBy("id ASC")
    @BatchSize(size = 30)
    @Builder.Default
    private List<MaintenanceAction> maintenanceActions = new ArrayList<>();
}
