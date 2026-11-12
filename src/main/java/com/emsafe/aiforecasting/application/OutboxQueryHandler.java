package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.ports.ForecastingOutboxPort;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.aiforecasting.domain.ports.PageResult;
import org.springframework.transaction.annotation.Transactional;

/** Eventos salientes pendientes de publicar, los más antiguos primero. */
public class OutboxQueryHandler {

    private final ForecastingOutboxPort outbox;

    public OutboxQueryHandler(ForecastingOutboxPort outbox) {
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public PageResult<OutboxEvent> pending(int page, int size) {
        ForecastingPaging.validate(page, size);
        return outbox.findPending(page, size);
    }
}
