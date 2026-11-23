package com.emsafe.alerts.application;

import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import com.emsafe.alerts.interfaces.rest.dto.CreateAlarmRequest;
import com.emsafe.alerts.interfaces.rest.dto.EscalateAlarmRequest;
import com.emsafe.alerts.domain.model.Alert;
import com.emsafe.alerts.infrastructure.notification.WorkerAlertNotifier;
import com.emsafe.alerts.infrastructure.persistence.AlertRepository;
import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.inbound.AlertEvidenceReported;
import com.emsafe.shared.exception.ResourceNotFoundException;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlarmService {

    private final AlertRepository alertRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final WorkerAlertNotifier notifier;

    /**
     * Lo que hace falta para avisar a un operario. Lo usa Occupational Health
     * para la alerta automática de exposición (ExposureAlertEventHandler,
     * 2026-11-22).
     *
     * @param type danger (límite superado) o warning (aviso preventivo)
     */
    public record WorkerAlert(Long organizationId, Long workerId, String sessionId,
                              String type, String icon, String title, String description) {
    }

    public List<AlertDto> getAll() {
        return alertRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(AlertDto::from).toList();
    }

    @Transactional
    public AlertDto create(CreateAlarmRequest req) {
        boolean specific = Alert.TO_SPECIFIC.equalsIgnoreCase(req.recipientType());

        Alert alert = Alert.builder()
                .type(req.type())
                .icon(req.icon())
                .title(req.title())
                .description(req.description())
                .relativeTime(StringUtils.hasText(req.relativeTime()) ? req.relativeTime() : "Just now")
                .recipientType(specific ? Alert.TO_SPECIFIC : Alert.TO_ALL)
                .sensor(req.sensor())
                // Nulos salvo que se cree una alerta de operario.
                .organizationId(req.organizationId())
                .workerId(req.workerId())
                .sessionId(req.sessionId())
                .build();

        if (specific && req.clientIds() != null && !req.clientIds().isEmpty()) {
            List<AppUser> clients = userRepository.findAllById(req.clientIds());
            alert.addressTo(
                    clients.stream().map(AppUser::getId).toList(),
                    clients.stream().map(AppUser::getName).collect(Collectors.joining(", ")));
        } else {
            alert.addressToEveryone();
        }

        return AlertDto.from(alertRepository.save(alert));
    }

    /**
     * Crea una alerta dirigida a un operario y la empuja por STOMP a la app y al
     * portal del SSO (después del commit, ver {@link WorkerAlertNotifier}).
     *
     * <p>Se une a la transacción de quien llama: si el que la pide se deshace, la
     * alerta también, y el aviso en vivo no sale.</p>
     */
    @Transactional
    public AlertDto raiseWorkerAlert(WorkerAlert request) {
        if (request.workerId() == null || request.organizationId() == null) {
            throw new IllegalArgumentException("A worker alert needs workerId and organizationId");
        }
        Alert alert = Alert.builder()
                .type(request.type())
                .icon(request.icon())
                .title(request.title())
                .description(request.description())
                .relativeTime("Just now")
                .organizationId(request.organizationId())
                .workerId(request.workerId())
                .sessionId(request.sessionId())
                .createdAt(now())
                .build();
        // Una alerta de operario no va a clientes: recipientClientIds queda vacío.
        alert.setRecipientType(Alert.TO_SPECIFIC);
        AlertDto saved = AlertDto.from(alertRepository.save(alert));
        notifier.notify(saved);
        return saved;
    }

    /** Las alertas del operario autenticado (GET /api/alarms/mine). */
    @Transactional(readOnly = true)
    public List<AlertDto> forWorker(String email) {
        AppUser worker = actor(email);
        return alertRepository.findByWorkerIdOrderByCreatedAtDesc(worker.getId())
                .stream().map(AlertDto::from).toList();
    }

    /** Las alertas de operario de la organización del SSO autenticado (GET /api/alarms/organization). */
    @Transactional(readOnly = true)
    public List<AlertDto> forOrganizationOf(String email) {
        AppUser sso = actor(email);
        if (sso.getOrganizationId() == null) {
            throw new AccessDeniedException("This account is not assigned to an organization");
        }
        return alertRepository
                .findByOrganizationIdAndWorkerIdIsNotNullOrderByCreatedAtDesc(sso.getOrganizationId())
                .stream().map(AlertDto::from).toList();
    }

    /**
     * Alertas {@code danger} de operario sin acuse ni escalamiento, creadas dentro
     * de la ventana. Las consulta el escalamiento automático (AlertEscalationJob).
     */
    @Transactional(readOnly = true)
    public List<AlertDto> awaitingEscalation(LocalDateTime createdAfter, LocalDateTime createdBefore) {
        return alertRepository.findAwaitingEscalation("danger", createdAfter, createdBefore)
                .stream().map(AlertDto::from).toList();
    }

    /**
     * Acuse pedido por un usuario. Un operario solo acusa <b>sus</b> alertas: hasta
     * el 2026-11-22 cualquier operario podía acusar la de otro, y el acuse es
     * evidencia de cumplimiento. El ADMIN sigue pudiendo.
     */
    public AlertDto acknowledgeAs(Long id, String email) {
        AppUser user = actor(email);
        if (user.hasRole(Role.WORKER)) {
            Alert alert = alertRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Alarm", id));
            if (alert.getWorkerId() != null && !alert.getWorkerId().equals(user.getId())) {
                throw new AccessDeniedException("A worker can only acknowledge their own alerts");
            }
        }
        return acknowledge(id);
    }

    /**
     * Escalamiento pedido por un usuario. Un SSO solo escala alertas de su
     * organización (antes podía escalar las de cualquiera). El ADMIN sigue pudiendo.
     */
    public AlertDto escalateAs(Long id, EscalateAlarmRequest req, String email) {
        AppUser user = actor(email);
        if (user.hasRole(Role.SSO_RESPONSIBLE)) {
            Alert alert = alertRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Alarm", id));
            if (alert.getOrganizationId() != null
                    && !alert.getOrganizationId().equals(user.getOrganizationId())) {
                throw new AccessDeniedException("An SSO can only escalate alerts of their organization");
            }
        }
        return escalate(id, req);
    }

    private AppUser actor(String email) {
        if (email == null) throw new AccessDeniedException("Access denied");
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
    }

    @Transactional
    public AlertDto resolve(Long id) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Alarm", id));
        alert.resolve();
        return AlertDto.from(alertRepository.save(alert));
    }

    /**
     * El operario acusa recibo. Publica {@code AlertAcknowledged} para que
     * Occupational Health deje evidencia (informe §5.6.3).
     *
     * <h2>Por qué este método NO es @Transactional</h2>
     *
     * <p>El consumidor de Occupational Health es un {@code @EventListener} con
     * {@code @Transactional} de propagación por defecto: si aquí hubiera una
     * transacción abierta, se uniría a ella y <b>un fallo suyo desharía el
     * acuse del operario</b>. Sin transacción propia, el {@code save()} del
     * repositorio confirma por su cuenta y el consumidor abre la suya.</p>
     *
     * <p>El fallo tampoco se propaga: la evidencia es contabilidad de otro
     * contexto y el operario no puede recibir un error por algo que sí quedó
     * registrado. Este contexto no tiene outbox, así que no hay reintento — es
     * la misma renuncia que {@code ShiftUpdatedEventListener}.</p>
     */
    public AlertDto acknowledge(Long id) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Alarm", id));
        LocalDateTime at = now();
        boolean first = alert.acknowledge(at);
        Alert saved = alertRepository.save(alert);

        if (first) {
            report(saved, AlertEvidenceReported.Kind.ACKNOWLEDGED, at);
        }
        AlertDto dto = AlertDto.from(saved);
        if (first) notifier.notify(dto);
        return dto;
    }

    /**
     * El aviso se escala al Responsable de SSO. Publica {@code AlertEscalated};
     * si no hubo acuse previo, Occupational Health sube además la prioridad de la
     * propuesta del operario (regla 10).
     *
     * <p>Mismas dos decisiones que {@link #acknowledge(Long)}: sin transacción
     * propia y sin propagar el fallo del consumidor.</p>
     */
    public AlertDto escalate(Long id, EscalateAlarmRequest req) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Alarm", id));
        LocalDateTime at = now();
        boolean first = alert.escalate(req.ssoResponsibleId(), req.reason(), at);
        Alert saved = alertRepository.save(alert);

        if (first) {
            report(saved, AlertEvidenceReported.Kind.ESCALATED, at);
        }
        AlertDto dto = AlertDto.from(saved);
        if (first) notifier.notify(dto);
        return dto;
    }

    /**
     * Microsegundos, no nanosegundos.
     *
     * <p>{@code LocalDateTime.now()} da nanosegundos y las columnas son
     * {@code DATETIME(6)}: sellar más precisión de la que se puede guardar
     * produce un valor que <b>no sobrevive al viaje</b>. La respuesta de la
     * escritura traería {@code .108483800} y cualquier lectura posterior
     * {@code .108484}, así que comparar por igualdad el valor devuelto contra el
     * leído falla —es el defecto que ya tiene {@code resolvedAt}, anotado como
     * pendiente—. Truncar aquí, donde entra la precisión, lo evita de raíz; y
     * aquí importa más que en {@code resolve()} porque esta marca es evidencia de
     * cumplimiento y además viaja en el evento al otro contexto, cuya columna es
     * también {@code DATETIME(6)}.</p>
     */
    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Los dos hechos del informe —{@code AlertAcknowledged} y
     * {@code AlertEscalated}— viajan en el tipo que Occupational Health declara
     * para su inbox, con {@code kind} como discriminador. Se construye aquí el
     * contrato del destino, igual que hace {@code DosimetryOutboxRelay}.
     *
     * <p>El {@code eventId} se compone del id de la alerta y del tipo de marca,
     * no es aleatorio: el inbox deduplica por él, y así un segundo intento del
     * mismo acuse no abre una segunda evidencia aunque llegue por otro camino.</p>
     */
    private void report(Alert alert, AlertEvidenceReported.Kind kind, LocalDateTime at) {
        String eventId = "alert-" + alert.getId() + "-" + kind.name().toLowerCase();
        EventEnvelope envelope = new EventEnvelope(eventId, 1, alert.getOrganizationId(),
                at, eventId, null);
        try {
            events.publishEvent(new AlertEvidenceReported(envelope, kind,
                    String.valueOf(alert.getId()), alert.getWorkerId(), alert.getSessionId(),
                    alert.getType(), alert.getSsoResponsibleId(), alert.getEscalationReason(), at));
        } catch (RuntimeException e) {
            log.warn("Alert evidence for alert {} ({}) could not be delivered: {}",
                    alert.getId(), kind, e.toString());
        }
    }

    @Transactional
    public void delete(Long id) {
        if (!alertRepository.existsById(id)) {
            throw new ResourceNotFoundException("Alarm", id);
        }
        alertRepository.deleteById(id);
    }
}
