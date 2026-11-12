package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Un mensaje ya procesado por un consumidor (informe §5.6.4,
 * InboxDeduplicator).
 *
 * La clave es la terna consumidor + organización + evento, no solo el evento:
 * el mismo hecho puede interesar a dos consumidores distintos, y cada uno debe
 * poder procesarlo una vez.
 */
@Entity
@Table(name = "processed_messages")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_name", nullable = false, length = 80)
    private String consumerName;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "processed_at", nullable = false)
    @Builder.Default
    private LocalDateTime processedAt = LocalDateTime.now();
}
