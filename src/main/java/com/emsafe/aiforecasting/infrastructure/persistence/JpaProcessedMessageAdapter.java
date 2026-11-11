package com.emsafe.aiforecasting.infrastructure.persistence;

import com.emsafe.aiforecasting.domain.ports.ProcessedMessagePort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Idempotencia del inbox. Se usa {@code persist} (no {@code merge}) para que un eventId repetido
 * viole la clave primaria en lugar de sobrescribir: dos entregas concurrentes del mismo evento
 * no pueden confirmarse ambas. {@code @Repository} traduce el error a
 * {@code DataIntegrityViolationException}.
 */
@Repository
public class JpaProcessedMessageAdapter implements ProcessedMessagePort {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public boolean isProcessed(String eventId) {
        return entityManager.find(ProcessedMessageEntity.class, eventId) != null;
    }

    @Override
    public void markProcessed(String eventId, Instant processedAt) {
        entityManager.persist(new ProcessedMessageEntity(eventId, processedAt));
        entityManager.flush();
    }
}
