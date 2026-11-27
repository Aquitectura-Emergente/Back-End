package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.personaldosimetry.application.DosimetryEventRecorded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Entrega las excedencias apenas se confirman, para que el aviso al operario no
 * espere a la pasada programada del relay, que queda como respaldo si esto falla.
 */
@Component
public class DosimetryOutboxImmediateRelay {
    private static final Logger log = LoggerFactory.getLogger(DosimetryOutboxImmediateRelay.class);

    private final ObjectProvider<DosimetryOutboxRelay> relay;
    private final TransactionTemplate tx;

    public DosimetryOutboxImmediateRelay(ObjectProvider<DosimetryOutboxRelay> relay,
                                         PlatformTransactionManager transactions) {
        this.relay = relay;
        this.tx = new TransactionTemplate(transactions);
        // Tras el commit la transacción original ya terminó: hace falta una nueva.
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener
    public void on(DosimetryEventRecorded recorded) {
        if (!DosimetryOutboxRelay.DELIVERABLE.contains(recorded.eventType())) return;
        DosimetryOutboxRelay target = relay.getIfAvailable();
        if (target == null) return;
        try {
            target.exclusively(() -> tx.executeWithoutResult(status -> target.drainOnce()));
        } catch (RuntimeException e) {
            log.warn("Immediate dosimetry relay failed, the scheduled pass will retry: {}", e.toString());
        }
    }
}
