package com.emsafe.fieldservice.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Evidence image (base64 data-URL) captured by the technician during a work order.
 */
@Entity
@Table(name = "work_order_evidence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkOrderEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Base64 data-URL of the image. */
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String image;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime uploadedAt = LocalDateTime.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;
}
