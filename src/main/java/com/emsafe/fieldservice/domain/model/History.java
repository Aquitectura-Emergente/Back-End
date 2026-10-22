package com.emsafe.fieldservice.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class History {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 30)
    private String orderId;

    private LocalDate completionDate;

    @Column(length = 20)
    private String completionTime;

    @Column(length = 150)
    private String client;

    @Column(length = 200)
    private String site;

    @Column(length = 20)
    private String serviceType;

    @Column(length = 100)
    private String technician;

    @Column(length = 5)
    private String technicianInitials;

    /** completed | cancelled */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "completed";

    /** ID del técnico asignado — usado para filtrar por técnico en el portal tech */
    private Long technicianId;

    /** Reference to the live work order — used to load the full detail view. */
    @Column(name = "work_order_id")
    private Long workOrderId;
}
