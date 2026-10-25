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

    // ─── Comportamiento del agregado ──────────────────────────────────────────
    // Las reglas viven aquí y no en el servicio de aplicación: así un flujo
    // nuevo no puede saltárselas por olvidar llamar a un validador externo.

    /**
     * Lleva la orden al estado pedido si la transición es legal.
     *
     * Al completarse se sella la fecha, una sola vez: si la orden ya se había
     * completado antes, se respeta la marca original.
     */
    public void transitionTo(WorkOrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new WorkOrderRuleException(
                    "Invalid status transition: " + status + " → " + target);
        }
        this.status = target;
        if (target == WorkOrderStatus.COMPLETED && completedAt == null) {
            this.completedAt = LocalDateTime.now();
        }
    }

    /**
     * Cancela la orden conservándola (soft-cancel): el detalle completo sigue
     * disponible desde el Historial y las listas activas la excluyen.
     */
    public void cancel(String reason) {
        transitionTo(WorkOrderStatus.CANCELLED);
        if (reason != null && !reason.isBlank()) {
            this.cancellationReason = reason;
        }
    }

    /**
     * Asigna el técnico y mantiene en sincronía el nombre e iniciales
     * desnormalizados, que existen para pintar las listas sin hacer JOIN.
     * Pasar null libera la asignación.
     */
    public void assignTechnician(AppUser newTechnician) {
        this.technician = newTechnician;
        this.technicianName = newTechnician != null ? newTechnician.getName() : null;
        this.technicianInitials = newTechnician != null ? newTechnician.getInitials() : null;
    }

    /** Un identificador legible derivado del id, estable una vez asignado. */
    public void assignOrderIdIfMissing() {
        if (orderId == null && id != null) {
            this.orderId = String.format("#WO-%04d", id);
        }
    }

    public void addEvidence(String image) {
        if (image == null || image.isBlank()) return;
        evidence.add(WorkOrderEvidence.builder().image(image).workOrder(this).build());
    }

    public void logActivity(String event, String time) {
        activityLog.add(ActivityLogEntry.builder()
                .event(event).logTime(time).workOrder(this).build());
    }

    public void addMaintenanceAction(Long deviceId, String deviceName,
                                     String action, String description) {
        if (action == null || action.isBlank()) return;
        maintenanceActions.add(MaintenanceAction.builder()
                .deviceId(deviceId).deviceName(deviceName)
                .action(action).description(description)
                .workOrder(this).build());
    }

    /** Actualiza el sensor si ya figura en la orden; si no, lo añade. */
    public void upsertSensor(String sensorId, String location, String sensorStatus) {
        sensors.stream()
                .filter(s -> s.getSensorId() != null && s.getSensorId().equals(sensorId))
                .findFirst()
                .ifPresentOrElse(
                        existing -> existing.setStatus(sensorStatus),
                        () -> sensors.add(Sensor.builder()
                                .sensorId(sensorId).location(location)
                                .status(sensorStatus).workOrder(this).build()));
    }

    /**
     * Arrancar un mantenimiento pone en marcha los equipos del cliente que
     * estaban marcados para revisión. Responder aquí evita que el servicio
     * tenga que redescubrir en qué transición exacta ocurre.
     */
    public boolean startsMaintenance(WorkOrderStatus previous) {
        return previous == WorkOrderStatus.PENDING
                && status == WorkOrderStatus.IN_PROGRESS
                && type == WorkOrderType.MAINTENANCE
                && clientUser != null;
    }
}
