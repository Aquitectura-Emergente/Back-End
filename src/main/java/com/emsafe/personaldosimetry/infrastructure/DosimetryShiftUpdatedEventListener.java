package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.occupationalhealth.application.events.ShiftUpdated;
import com.emsafe.personaldosimetry.application.PersonalDosimetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Adaptador de entrada de la relación Partnership (Occupational Health →
 * Personal Dosimetry, informe §4.2.5 y §5.4.3).
 *
 * <p>Escucha {@link ShiftUpdated} después del commit ({@code AFTER_COMMIT}) y en
 * una transacción nueva ({@code REQUIRES_NEW}), igual que
 * {@code ShiftUpdatedEventListener} de AI Forecasting: cuando se ejecuta, la
 * rotación o reubicación ya está confirmada en base de datos, y un turno de OH
 * sin sesión de dosimetría o sin límite configurado para el puesto nuevo no
 * revierte ni rompe la operación del SSO.</p>
 */
@Component
public class DosimetryShiftUpdatedEventListener {

    private static final Logger log = LoggerFactory.getLogger(DosimetryShiftUpdatedEventListener.class);
    private static final int MAX_TRACKED_EVENTS = 2048;

    private final PersonalDosimetryService dosimetry;
    private final Set<String> processedEventKeys = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_TRACKED_EVENTS;
                }
            }));

    public DosimetryShiftUpdatedEventListener(PersonalDosimetryService dosimetry) {
        this.dosimetry = dosimetry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(ShiftUpdated event) {
        if (event == null || event.envelope() == null) {
            return;
        }
        String dedupKey = dedupKey(event);
        if (dedupKey != null && !processedEventKeys.add(dedupKey)) {
            log.debug("ShiftUpdated {} already processed by Personal Dosimetry", event.envelope().eventId());
            return;
        }
        try {
            Instant effectiveAt = event.effectiveAt() != null
                    ? event.effectiveAt().toInstant(ZoneOffset.UTC)
                    : null;
            var updated = dosimetry.applyShiftUpdate(
                    event.envelope().organizationId(),
                    event.sessionId(),
                    event.workerId(),
                    event.newPositionId(),
                    effectiveAt);
            if (updated.isPresent()) {
                var s = updated.get();
                log.info("Personal Dosimetry applied ShiftUpdated {} on session {} (position={}, limitVersionId={}, limitIndex={})",
                        event.envelope().eventId(), s.id(), s.jobPositionId(), s.limitVersionId(), s.limitIndex());
            } else {
                log.debug("Personal Dosimetry ignored ShiftUpdated {}: no matching open session (sessionId={}, workerId={})",
                        event.envelope().eventId(), event.sessionId(), event.workerId());
            }
        } catch (RuntimeException e) {
            if (dedupKey != null) {
                processedEventKeys.remove(dedupKey);
            }
            log.warn("Personal Dosimetry could not apply ShiftUpdated {} (sessionId={}, workerId={}): {}",
                    event.envelope().eventId(), event.sessionId(), event.workerId(), e.toString());
        }
    }

    private static String dedupKey(ShiftUpdated event) {
        String eventId = event.envelope().eventId();
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        String scope = (event.sessionId() != null && !event.sessionId().isBlank())
                ? event.sessionId()
                : "worker:" + event.envelope().organizationId() + ":" + event.workerId();
        return scope + ":" + eventId;
    }
}
