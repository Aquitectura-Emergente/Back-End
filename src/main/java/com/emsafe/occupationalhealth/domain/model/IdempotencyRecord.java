package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Una mutación ya ejecutada con una Idempotency-Key (informe §5.6.2).
 *
 * Guarda a qué recurso resolvió la primera llamada, para poder devolver el
 * mismo resultado si la petición se repite. La clave se acota por endpoint: la
 * misma key en dos operaciones distintas son dos intenciones distintas.
 */
@Entity
@Table(name = "idempotency_records")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 120)
    private String endpoint;

    /** El recurso que creó o afectó la primera llamada. */
    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
