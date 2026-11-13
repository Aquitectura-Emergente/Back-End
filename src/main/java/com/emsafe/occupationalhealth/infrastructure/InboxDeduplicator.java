package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.domain.model.ProcessedMessage;
import com.emsafe.occupationalhealth.infrastructure.persistence.ProcessedMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Descarta los mensajes que un consumidor ya procesó (informe §5.6.4).
 *
 * Un broker entrega al menos una vez, así que un consumidor tiene que ser capaz
 * de ver el mismo hecho dos veces sin duplicar su efecto. Hoy los eventos
 * llegan en proceso y no se reentregan, pero el inbox se implementa igual: es
 * lo que permitirá enchufar un broker sin revisar los cuatro handlers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxDeduplicator {

    private final ProcessedMessageRepository processedMessages;

    /**
     * Marca el mensaje como procesado y dice si hay que seguir.
     *
     * Devuelve false —ya visto— tanto si el registro existía como si la
     * inserción choca con el UNIQUE: esa colisión es exactamente la condición de
     * carrera que el índice está ahí para resolver, y tratarla como duplicado es
     * la respuesta correcta, no un error.
     */
    public boolean markAsProcessed(String consumerName, EventEnvelope envelope) {
        if (envelope == null || envelope.eventId() == null) {
            // Sin identidad no se puede deduplicar; se procesa y se avisa.
            log.warn("Event for consumer {} arrived without eventId", consumerName);
            return true;
        }
        if (processedMessages.existsByConsumerNameAndOrganizationIdAndEventId(
                consumerName, envelope.organizationId(), envelope.eventId())) {
            log.debug("Consumer {} already processed event {}", consumerName, envelope.eventId());
            return false;
        }
        try {
            processedMessages.save(ProcessedMessage.builder()
                    .consumerName(consumerName)
                    .organizationId(envelope.organizationId())
                    .eventId(envelope.eventId())
                    .build());
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.debug("Consumer {} lost the race for event {}", consumerName, envelope.eventId());
            return false;
        }
    }
}
