package com.emsafe.fieldservice.domain.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "activity_log_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String event;

    @Column(length = 50)
    private String logTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;
}
