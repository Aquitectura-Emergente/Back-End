package com.emsafe.personaldosimetry.infrastructure;

import com.emsafe.aiforecasting.application.commands.ExposureWarningRaisedCommand;
import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.inbound.ExposureSessionClosed;
import com.emsafe.occupationalhealth.application.events.inbound.ExposureSessionOpened;
import com.emsafe.occupationalhealth.application.events.inbound.ExposureWarningRaised;
import com.emsafe.occupationalhealth.application.events.inbound.OccupationalLimitExceeded;
import com.emsafe.personaldosimetry.domain.model.DosimetryEvent;
import com.emsafe.personaldosimetry.infrastructure.persistence.DosimetryEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Entrega a Occupational Health las excedencias que este contexto confirma.
 *
 * <p>{@code personal_dosimetry_outbox} se escribía y nadie la leía: su API REST
 * existe para que un publicador externo la drene y ese publicador no existía, así
 * que una excedencia no abría ninguna medida de mitigación salvo que alguien
 * hiciera el POST a mano al inbox.</p>
 *
 * <h2>Entrega cuatro de los seis tipos, a dos contextos distintos</h2>
 *
 * <p>Hacia Occupational Health, {@code OccupationalLimitExceeded} abre una medida
 * de mitigación y {@code ExposureSessionClosed} alimenta la proyección que
 * exportan los reportes de cumplimiento. Hacia AI Forecasting,
 * {@code ExposureSessionOpened} da de alta el modelo de lectura de la sesión y
 * {@code ExposureWarningRaised} dispara la evaluación preventiva.</p>
 *
 * <p>Desde el 2026-11-22 {@code ExposureWarningRaised} va <b>a los dos</b>: a AI
 * Forecasting como antes y, además, a Occupational Health, que con él manda el
 * aviso preventivo al operario. Primero se entrega a Occupational Health, para
 * que un 409 de AI (snapshot todavía sin crear) no retrase el aviso. Si AI falla,
 * el evento se queda pendiente y en la pasada siguiente se vuelve a entregar a
 * los dos: Occupational Health lo descarta por su inbox (deduplica por
 * {@code eventId}).</p>
 *
 * <p>Los otros dos —{@code PersonalReadingArrivedLate} y
 * {@code ExposureSessionCloseRequested}— se quedan <b>pendientes sin marcar</b>.
 * Es deliberado y distinto de lo que hace el relay de AI Forecasting con
 * {@code PredictiveRiskDetected}: allí se marca porque nadie lo consumirá nunca,
 * mientras aquí tienen destino previsto y marcarlos perdería el hecho. Por eso la
 * consulta filtra por tipo en vez de leer todos los pendientes: si no, los no
 * entregables encabezarían la cola para siempre y taparían a los demás.</p>
 *
 * <h2>La apertura tiene que llegar antes que el aviso</h2>
 *
 * <p>El inbox {@code exposure-warning-raised} responde 409 si la sesión no tiene
 * snapshot, así que {@code ExposureSessionOpened} es su prerrequisito. La consulta
 * ordena por {@code occurredAt}, que casi siempre basta, pero <b>no siempre</b>:
 * una sesión se puede abrir con fecha de inicio retroactiva, y entonces una
 * lectura de dentro del turno tiene un {@code occurredAt} anterior al de la
 * apertura y se adelanta en la cola.</p>
 *
 * <p>No se fuerza el orden porque el reintento ya lo resuelve: el aviso falla con
 * 409, se queda pendiente y la pasada siguiente lo encuentra con el snapshot ya
 * creado. Cuesta una pasada, no un hecho perdido. Forzarlo exigiría ordenar por
 * tipo además de por tiempo, y eso sí rompería el orden causal del resto.</p>
 *
 * <h2>Sobre el mapeo del índice a «dosis»</h2>
 *
 * <p>{@code accumulatedIndex} viaja en el campo {@code dose} del inbox. No es un
 * abuso: {@code ExposureIndex} advierte que es un índice de demostración y no una
 * dosis certificada, y el destino <b>conserva la unidad</b>
 * ({@code DoseSnapshot.of(dose, unit, …)}), que aquí va como {@code uT*s}. Lo que
 * no se puede es perder esa unidad por el camino.</p>
 */
@Component
@ConditionalOnProperty(name = "emsafe.personal-dosimetry.outbox-relay-enabled",
        havingValue = "true", matchIfMissing = true)
public class DosimetryOutboxRelay {

    /** Los mismos nombres que usa PersonalDosimetryService al emitir. */
    public static final String LIMIT_EXCEEDED = "OccupationalLimitExceeded";
    public static final String SESSION_CLOSED = "ExposureSessionClosed";
    public static final String SESSION_OPENED = "ExposureSessionOpened";
    public static final String WARNING_RAISED = "ExposureWarningRaised";
    /** Lo que este relay sabe entregar. El resto se queda pendiente. */
    static final List<String> DELIVERABLE =
            List.of(LIMIT_EXCEEDED, SESSION_CLOSED, SESSION_OPENED, WARNING_RAISED);
    private static final int BATCH = 50;
    private static final Logger log = LoggerFactory.getLogger(DosimetryOutboxRelay.class);
    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {
    };

    private final DosimetryEventRepository events;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper json;
    private final Clock clock;

    public DosimetryOutboxRelay(DosimetryEventRepository events, ApplicationEventPublisher publisher,
                                ObjectMapper json, Clock clock) {
        this.events = events;
        this.publisher = publisher;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${emsafe.personal-dosimetry.outbox-relay-delay-ms:30000}")
    public synchronized void drainScheduled() {
        try {
            drainOnce();
        } catch (RuntimeException e) {
            log.warn("Dosimetry outbox relay pass failed: {}", e.toString());
        }
    }

    /**
     * Ejecuta una pasada sin solaparse con la programada, para que las dos no
     * entreguen el mismo evento a la vez.
     */
    public synchronized void exclusively(Runnable pass) {
        pass.run();
    }

    /**
     * Una pasada. Público y separado del programado para poder ejercitarlo sin
     * depender del reloj del scheduler.
     *
     * @return excedencias entregadas
     */
    @Transactional
    public int drainOnce() {
        List<DosimetryEvent> pending = events
                .findByPublishedAtIsNullAndEventTypeInOrderByOccurredAtAsc(
                        DELIVERABLE, PageRequest.of(0, BATCH))
                .getContent();
        int delivered = 0;
        for (DosimetryEvent event : pending) {
            if (deliver(event)) {
                event.acknowledge(clock.instant());
                events.save(event);
                delivered++;
            }
        }
        if (delivered > 0) {
            log.info("Dosimetry relay delivered {} of {} pending event(s)", delivered, pending.size());
        }
        return delivered;
    }

    /**
     * Una pasada en la que cada evento se entrega y se confirma en su propia
     * transacción. Así un evento que falla (y deja marcada su transacción para
     * deshacerse) no arrastra a los demás de la pasada, como pasaba al envolver
     * {@link #drainOnce()} entero en una sola transacción.
     *
     * @return eventos entregados
     */
    public int drainEachIsolated(TransactionTemplate tx) {
        List<String> pending = tx.execute(status -> events
                .findByPublishedAtIsNullAndEventTypeInOrderByOccurredAtAsc(DELIVERABLE, PageRequest.of(0, BATCH))
                .getContent().stream().map(DosimetryEvent::getId).toList());
        int delivered = 0;
        for (String id : pending == null ? List.<String>of() : pending) {
            try {
                Boolean ok = tx.execute(status -> events.findById(id)
                        .filter(event -> event.getPublishedAt() == null && deliver(event))
                        .map(event -> {
                            event.acknowledge(clock.instant());
                            events.save(event);
                            return true;
                        })
                        .orElse(false));
                if (Boolean.TRUE.equals(ok)) delivered++;
            } catch (RuntimeException e) {
                // Su transacción quedó deshecha; la pasada programada lo reintentará.
                log.warn("Could not deliver dosimetry event {}: {}", id, e.toString());
            }
        }
        if (delivered > 0) {
            log.info("Dosimetry relay delivered {} of {} pending event(s)", delivered, pending.size());
        }
        return delivered;
    }

    private boolean deliver(DosimetryEvent event) {
        try {
            publisher.publishEvent(switch (event.getEventType()) {
                case SESSION_CLOSED -> toSessionClosed(event);
                case SESSION_OPENED -> {
                    // Salud ocupacional abre el turno del operario (después del commit).
                    publisher.publishEvent(toOccupationalHealthOpened(event));
                    yield toSnapshotUpsert(event);
                }
                case WARNING_RAISED -> {
                    publisher.publishEvent(toOccupationalHealthWarning(event));
                    yield toWarningRaised(event);
                }
                default -> toLimitExceeded(event);
            });
            return true;
        } catch (RuntimeException e) {
            // Se queda pendiente. El inbox deduplica por eventId, así que el
            // reintento de la pasada siguiente no abre una segunda medida.
            log.warn("Could not deliver {} {}: {}", event.getEventType(), event.getId(), e.toString());
            return false;
        }
    }

    /**
     * La jornada cerrada alimenta la proyección que exportan los reportes de
     * cumplimiento. {@code snapshotId} va nulo: este contexto no versiona
     * instantáneas de la sesión.
     */
    private ExposureSessionClosed toSessionClosed(DosimetryEvent event) {
        Map<String, Object> payload = read(event);
        return new ExposureSessionClosed(envelopeOf(event, payload),
                text(payload.get("sessionId")),
                null,
                number(payload.get("workerId")),
                decimal(payload.get("accumulatedIndex")),
                text(payload.get("unit")),
                decimal(payload.get("limitIndex")),
                text(payload.get("methodId")),
                coverage(payload),
                finalizedAt(event, payload));
    }

    /**
     * {@code finalizedAt} se añadió al payload el 2026-11-12, así que los eventos
     * emitidos antes no lo llevan y siguen pendientes en la tabla. El destino lo
     * exige no nulo —su columna es NOT NULL—, de modo que sin respaldo se
     * reintentarían y fallarían en cada pasada, para siempre.
     *
     * <p>Se recurre al {@code occurredAt} del propio evento, que es cuándo se
     * emitió: para un hecho que se publica en la misma transacción que lo produce,
     * es el instante del suceso.</p>
     */
    private LocalDateTime finalizedAt(DosimetryEvent event, Map<String, Object> payload) {
        LocalDateTime declared = dateTime(payload.get("finalizedAt"));
        return declared != null ? declared : utc(event.getOccurredAt());
    }

    /**
     * El destino guarda la cobertura como texto. Se compone con el porcentaje y
     * si está completa, que es lo que dosimetría publica: así el reporte dice
     * «98.50% (incompleta)» en vez de perder uno de los dos datos.
     */
    private String coverage(Map<String, Object> payload) {
        BigDecimal percentage = decimal(payload.get("coveragePercentage"));
        if (percentage == null) return null;
        boolean complete = Boolean.TRUE.equals(payload.get("coverageComplete"));
        // Se fija la escala a dos decimales porque el viaje por JSON se come los
        // ceros finales: 98.50 vuelve como 98.5. Dosimetría calcula la cobertura
        // con dos decimales y el destino guarda los porcentajes como
        // DECIMAL(6,2), así que esto restaura lo que ambos lados esperan en vez
        // de dejar que el formato dependa del número que haya tocado.
        return percentage.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "% " + (complete ? "(completa)" : "(incompleta)");
    }

    /**
     * El id del evento es el eventId del sobre: de él depende que el inbox
     * reconozca un reintento y no abra una segunda medida. Este outbox no guarda
     * correlationId, así que se usa el propio id, igual que hace
     * {@code EventEnvelope.of} para una cadena que empieza.
     */
    private EventEnvelope envelopeOf(DosimetryEvent event, Map<String, Object> payload) {
        return new EventEnvelope(event.getId(), schemaVersion(payload),
                event.getOrganizationId(), utc(event.getOccurredAt()), event.getId(), null);
    }

    /** La jornada abierta, para que Occupational Health abra el turno en la zona del puesto. */
    private ExposureSessionOpened toOccupationalHealthOpened(
            DosimetryEvent event) {
        Map<String, Object> payload = read(event);
        return new ExposureSessionOpened(
                envelopeOf(event, payload),
                text(payload.get("sessionId")),
                number(payload.get("workerId")),
                number(payload.get("positionId")));
    }

    private OccupationalLimitExceeded toLimitExceeded(DosimetryEvent event) {
        Map<String, Object> payload = read(event);
        return new OccupationalLimitExceeded(envelopeOf(event, payload),
                text(payload.get("sessionId")),
                // No hay segmento: este contexto acumula por sesión, no por tramo.
                null,
                number(payload.get("workerId")),
                decimal(payload.get("accumulatedIndex")),
                text(payload.get("unit")),
                decimal(payload.get("limitIndex")),
                number(payload.get("limitVersionId")),
                text(payload.get("methodId")),
                dateTime(payload.get("capturedAt")),
                dateTime(payload.get("detectedAt")));
    }

    /**
     * La apertura de jornada da de alta el modelo de lectura de AI Forecasting.
     *
     * <p>{@code zoneId} va nulo: dosimetría no sabe en qué zona de planta está el
     * operario —ese dato es de Occupational Health y llega después por
     * {@code ShiftUpdated}, que el snapshot conserva al fusionar—. El puesto sí
     * viaja, porque la sesión lo guarda desde que se eligió el límite.</p>
     *
     * <p>{@code capturedAt} se iguala al inicio de la sesión, que es lo que el
     * propio dominio considera la última captura conocida al abrir
     * ({@code ExposureSession.open} fija {@code latestCaptureTo = startedAt}). No
     * se usa el instante de la emisión: con una apertura retroactiva, adelantaría
     * el reloj del snapshot e ignoraría las lecturas del tramo ya transcurrido,
     * que son más antiguas y se descartan por ser anteriores.</p>
     */
    private UpsertExposureSnapshotCommand toSnapshotUpsert(DosimetryEvent event) {
        Map<String, Object> payload = read(event);
        Instant startedAt = instant(payload.get("startedAt"));
        return new UpsertExposureSnapshotCommand(event.getId(), event.getId(),
                event.getOrganizationId(),
                number(payload.get("workerId")),
                text(payload.get("sessionId")),
                null,
                number(payload.get("positionId")),
                decimal(payload.get("accumulatedIndex")),
                decimal(payload.get("limitIndex")),
                startedAt,
                null,
                startedAt);
    }

    /**
     * El aviso dispara la evaluación preventiva. Es el único de los cuatro cables
     * sin divergencia de vocabulario: los once campos del comando se llaman igual
     * que los del payload.
     *
     * <p>{@code limitVersionId} viaja unificado como {@code Long} tanto hacia AI
     * Forecasting como hacia Occupational Health, coincidiendo con el tipo real
     * del identificador del límite en este contexto.</p>
     */
    private ExposureWarningRaisedCommand toWarningRaised(DosimetryEvent event) {
        Map<String, Object> payload = read(event);
        return new ExposureWarningRaisedCommand(event.getId(), event.getId(),
                event.getOrganizationId(),
                text(payload.get("sessionId")),
                number(payload.get("workerId")),
                decimal(payload.get("accumulatedIndex")),
                decimal(payload.get("limitIndex")),
                number(payload.get("limitVersionId")),
                instant(payload.get("capturedAt")),
                text(payload.get("methodId")),
                text(payload.get("unit")));
    }

    /**
     * El mismo aviso, en el vocabulario de Occupational Health. Los campos son
     * los de {@link #toLimitExceeded}, salvo {@code segmentId}, que este hecho no
     * tiene.
     */
    private ExposureWarningRaised toOccupationalHealthWarning(DosimetryEvent event) {
        Map<String, Object> payload = read(event);
        return new ExposureWarningRaised(envelopeOf(event, payload),
                text(payload.get("sessionId")),
                number(payload.get("workerId")),
                decimal(payload.get("accumulatedIndex")),
                text(payload.get("unit")),
                decimal(payload.get("limitIndex")),
                number(payload.get("limitVersionId")),
                text(payload.get("methodId")),
                dateTime(payload.get("capturedAt")),
                dateTime(payload.get("detectedAt")));
    }

    private Map<String, Object> read(DosimetryEvent event) {
        try {
            return json.readValue(event.getPayload(), PAYLOAD);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt payload in dosimetry event " + event.getId(), e);
        }
    }

    private int schemaVersion(Map<String, Object> payload) {
        Long version = number(payload.get("schemaVersion"));
        return version == null ? 1 : version.intValue();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Se convierte con el mismo ObjectMapper que escribió el payload, en vez de
     * parsear a mano: así da igual si serializa las fechas como texto ISO o como
     * número, y un cambio de configuración no rompe esto en silencio.
     */
    private BigDecimal decimal(Object value) {
        return value == null ? null : json.convertValue(value, BigDecimal.class);
    }

    private LocalDateTime dateTime(Object value) {
        return value == null ? null : utc(json.convertValue(value, Instant.class));
    }

    /** AI Forecasting trabaja en {@code Instant}; Occupational Health, en local UTC. */
    private Instant instant(Object value) {
        return value == null ? null : json.convertValue(value, Instant.class);
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
