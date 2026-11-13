package com.emsafe.devicemanagement.infrastructure.mqtt;

import com.emsafe.devicemanagement.application.PlugCommandService.PlugCommandAccepted;
import com.emsafe.devicemanagement.application.ports.PlugCommandPublisher;
import com.emsafe.devicemanagement.domain.repositories.PlugCommandRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/**
 * Envía la orden al broker una vez confirmada en la base. No se reintenta: el
 * Edge ignora órdenes con más de 60 s, así que una orden no enviada queda
 * SEND_FAILED y el usuario la repite.
 */
@Component
@RequiredArgsConstructor
public class PlugCommandDispatcher {
    private static final Logger log = LoggerFactory.getLogger(PlugCommandDispatcher.class);

    private final PlugCommandRepository commands;
    private final PlugCommandPublisher publisher;
    private final PlatformTransactionManager transactions;
    private final Clock clock;

    @TransactionalEventListener
    public void dispatch(PlugCommandAccepted accepted) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> commands.findByCommandId(accepted.commandId()).ifPresent(command -> {
            if (!publisher.publish(command)) {
                log.warn("Plug command {} for {} could not be published", command.getCommandId(),
                        command.getPlugSerial());
                command.markSendFailed(clock.instant());
                commands.save(command);
            }
        }));
    }
}
