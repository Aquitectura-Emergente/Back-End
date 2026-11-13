package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.ForecastingEventFactory;
import com.emsafe.aiforecasting.domain.ports.ForecastingOutboxPort;
import com.emsafe.aiforecasting.domain.ports.OutboxEvent;
import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.inbound.RotationSuggested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drena {@code af_outbox} y entrega los hechos a quien los consume.
 *
 * <p>El otro lado del puente que abre {@link ShiftUpdatedEventListener}. AI
 * Forecasting escribía {@code RotationSuggested} en su tabla de outbox y nadie
 * la leía: su controller REST existe para que un publicador externo la drene, y
 * ese publicador no existía. Resultado: una sugerencia del agente no llegaba a
 * Occupational Health salvo que alguien hiciera el POST a mano.</p>
 *
 * <p>El payload ya venía preparado para esto —{@code ForecastingEventFactory}
 * lo documenta como «cuerpo plano compatible con RotationSuggestedInput»—, así
 * que aquí solo se traduce y se publica.</p>
 *
 * <h2>Por qué este sí tiene reintento y el otro sentido no</h2>
 *
 * <p>Aquí hay tabla: un evento se marca publicado <b>después</b> de entregarlo,
 * y si la entrega falla se queda pendiente y vuelve en la pasada siguiente. Se
 * conserva el {@code eventId} del outbox como eventId del sobre, de modo que el
 * {@code InboxDeduplicator} de Occupational Health reconozca el reintento y no
 * abra dos propuestas. Es entrega al-menos-una-vez con consumidor idempotente.</p>
 *
 * <p>Los hechos sin consumidor hoy —{@code PredictiveRiskDetected}— también se
 * marcan, o encabezarían la cola para siempre e impedirían ver los siguientes.
 * Marcar no borra: solo escribe {@code published_at}, y el evento sigue entero
 * en la tabla y consultable por el outbox REST.</p>
 */
@Component
@ConditionalOnProperty(name = "emsafe.ai-forecasting.outbox-relay-enabled",
        havingValue = "true", matchIfMissing = true)
public class ForecastingOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(ForecastingOutboxRelay.class);
    private static final int BATCH = 50;

    private final ForecastingOutboxPort outbox;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ForecastingOutboxRelay(ForecastingOutboxPort outbox, ApplicationEventPublisher events,
                                  @org.springframework.beans.factory.annotation.Qualifier(
                                          AiForecastingConfiguration.CLOCK) Clock clock) {
        this.outbox = outbox;
        this.events = events;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${emsafe.ai-forecasting.outbox-relay-delay-ms:30000}")
    public void drainScheduled() {
        try {
            drainOnce();
        } catch (RuntimeException e) {
            // Nunca dejar que el scheduler muera por un fallo puntual de lectura.
            log.warn("Outbox relay pass failed: {}", e.toString());
        }
    }

    /**
     * Una pasada. Separado del método programado para poder ejercitarlo sin
     * depender del reloj del scheduler.
     *
     * @return eventos marcados como publicados en esta pasada
     */
    public int drainOnce() {
        List<OutboxEvent> pending = outbox.findPending(0, BATCH).content();
        int acknowledged = 0;
        for (OutboxEvent event : pending) {
            if (deliver(event)) {
                outbox.acknowledge(event.eventId(), clock.instant());
                acknowledged++;
            }
        }
        if (acknowledged > 0) {
            log.info("Outbox relay delivered {} of {} pending event(s)", acknowledged, pending.size());
        }
        return acknowledged;
    }

    /** @return true si se puede marcar como publicado */
    private boolean deliver(OutboxEvent event) {
        if (!ForecastingEventFactory.ROTATION_SUGGESTED.equals(event.eventType())) {
            log.debug("Outbox event {} of type {} has no consumer yet; marking it published",
                    event.eventId(), event.eventType());
            return true;
        }
        try {
            events.publishEvent(toOccupationalHealthEvent(event));
            return true;
        } catch (RuntimeException e) {
            // Se queda pendiente: la pasada siguiente lo reintenta y el inbox de
            // Occupational Health deduplica por eventId.
            log.warn("Could not deliver RotationSuggested {}: {}", event.eventId(), e.toString());
            return false;
        }
    }

    private static RotationSuggested toOccupationalHealthEvent(OutboxEvent event) {
        Map<String, Object> payload = event.payload();
        EventEnvelope envelope = new EventEnvelope(
                event.eventId(),
                event.schemaVersion(),
                event.organizationId(),
                LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
                event.correlationId(),
                event.causationId());
        return new RotationSuggested(envelope,
                text(payload.get("suggestionId")),
                number(payload.get("workerId")),
                numbers(payload.get("candidateWorkerIds")),
                dateTime(payload.get("projectedExceedanceAt")),
                text(payload.get("criticality")),
                text(payload.get("rationale")));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** El JSON vuelve con Long, pero no se da por supuesto: un Integer rompería el cast. */
    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static List<Long> numbers(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        List<Long> ids = new ArrayList<>(raw.size());
        for (Object item : raw) {
            Long id = number(item);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /** ISO-8601 local y en UTC, como lo escribe ForecastingEventFactory. */
    private static LocalDateTime dateTime(Object value) {
        String raw = text(value);
        if (raw == null || raw.isBlank()) return null;
        return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
