package com.emsafe.alerts.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** danger | info | success | warning */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(length = 50)
    private String icon;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String relativeTime;

    /** all | specific — who the alarm is addressed to. */
    @Column(name = "recipient_type", length = 30)
    private String recipientType;

    /** Denormalized client name(s) for display in the alarm detail. */
    @Column(name = "client_name", length = 300)
    private String clientName;

    /** Sensor referenced by the alarm (for the detail view). */
    @Column(length = 100)
    private String sensor;

    /** Specific client recipients (user ids). Empty when recipientType = all. */
    @ElementCollection
    @CollectionTable(name = "alert_recipient_clients",
            joinColumns = @JoinColumn(name = "alert_id"))
    @Column(name = "client_id")
    @Builder.Default
    private List<Long> recipientClientIds = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private Boolean resolved = false;

    @Column
    private LocalDateTime resolvedAt;
}
