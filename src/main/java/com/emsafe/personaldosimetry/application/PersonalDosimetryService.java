package com.emsafe.personaldosimetry.application;

import com.emsafe.personaldosimetry.domain.model.*;
import com.emsafe.personaldosimetry.infrastructure.persistence.*;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.*;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryResources;
import com.emsafe.shared.dto.PageResponse;
import com.emsafe.shared.exception.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class PersonalDosimetryService {
    private final DosimetryMembershipRepository memberships;
    private final OccupationalLimitRepository limits;
    private final ExposureSessionRepository sessions;
    private final PersonalReadingRepository readings;
    private final DosimetryEventRepository events;
    private final DosimetryDirectory directory;
    private final ObjectMapper json;
    private final Clock clock;

    public DosimetryResources.Membership grantMembership(DosimetryActor actor, GrantMembership request) {
        actor.requireAdministrator();
        var organization = directory.userById(request.organizationId());
        var user = directory.userById(request.userId());
        if (!organization.active() || !organization.clientAccount() || !organization.company()
                || !user.active() || !user.clientAccount()) {
            throw DosimetryRuleException.invalid("An active company and an active client account are required");
        }
        var existing = memberships.findById(request.userId());
        if (existing.isPresent()) {
            var m = existing.get();
            if (!m.getOrganizationId().equals(request.organizationId()) || m.getRole() != request.role()) {
                throw DosimetryRuleException.conflict("Membership already exists; reassignment requires an explicit migration");
            }
            return DosimetryResources.Membership.from(m);
        }
        var m = memberships.save(new DosimetryMembership(request.userId(), request.organizationId(),
                request.role(), actor.userId(), now()));
        emit(m.getOrganizationId(), "DosimetryMembershipGranted",
                Map.of("userId", m.getUserId(), "role", m.getRole(), "grantedBy", actor.userId()), now());
        return DosimetryResources.Membership.from(m);
    }

    public DosimetryResources.Limit configureLimit(DosimetryActor actor, Long organizationId, ConfigureLimit request) {
        actor.requireSupervisor(organizationId);
        var organization = directory.userById(organizationId);
        if (!organization.active() || !organization.company()) {
            throw DosimetryRuleException.invalid("An active company account is required");
        }
        int version = limits.findFirstByOrganizationIdAndJobPositionIdOrderByVersionNumberDesc(
                organizationId, request.jobPositionId()).map(l -> l.getVersionNumber() + 1).orElse(1);
        var limit = limits.saveAndFlush(new OccupationalLimit(organizationId, request.jobPositionId(), version,
                request.limitIndex(), request.warningRatio(), request.effectiveFrom(), request.reference(),
                actor.userId(), now()));
        // positionId, no jobPositionId: en los payloads de evento el nombre es el
        // del spec §5.6 («previousPositionId, newPositionId»), que es el que ya
        // usan ShiftUpdated y los inbox de AI Forecasting. La entidad y la columna
        // siguen siendo jobPositionId; esto es solo el vocabulario de integración.
        emit(organizationId, "OccupationalLimitConfigured",
                Map.of("limitVersionId", limit.getId(), "version", version, "positionId", limit.getJobPositionId(),
                        "changedBy", actor.userId(), "methodId", ExposureIndex.METHOD), now());
        return DosimetryResources.Limit.from(limit);
    }

    @Transactional(readOnly = true)
    public PageResponse<DosimetryResources.Limit> listLimits(DosimetryActor actor, Long organizationId,
                                                           int page, int size) {
        actor.requireSupervisor(organizationId);
        return PageResponse.of(limits.findByOrganizationIdOrderByJobPositionIdAscVersionNumberDesc(
                organizationId, page(page, size)).map(DosimetryResources.Limit::from));
    }

    public DosimetryResources.Session openSession(DosimetryActor actor, OpenSession request) {
        var device = directory.deviceById(request.deviceId());
        if (!device.active() || device.organizationId() == null) {
            throw DosimetryRuleException.invalid("Dosimeter must be active and assigned to a company");
        }
        actor.requireSupervisor(device.organizationId());
        var organization = directory.userById(device.organizationId());
        if (!organization.active() || !organization.company()) {
            throw DosimetryRuleException.invalid("Dosimeter must belong to an active company");
        }
        var worker = directory.userById(request.workerId());
        var member = memberships.findById(request.workerId())
                .orElseThrow(() -> DosimetryRuleException.invalid("Worker has no dosimetry membership"));
        if (!worker.active() || member.getRole() != MembershipRole.OPERATOR
                || !member.getOrganizationId().equals(device.organizationId())) {
            throw DosimetryRuleException.invalid("Worker and dosimeter must belong to the same company");
        }
        Instant now = now();
        Instant startedAt = request.startedAt() == null ? now : request.startedAt();
        ExposureIndex.timestamp(startedAt);
        if (startedAt.isAfter(now)) throw DosimetryRuleException.invalid("Session cannot start in the future");
        if (sessions.hasConflictingAssignment(request.workerId(), request.deviceId(), startedAt)) {
            throw DosimetryRuleException.conflict("Worker or dosimeter already has a conflicting assignment");
        }
        var limit = limits
                .findFirstByOrganizationIdAndJobPositionIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDescVersionNumberDesc(
                        device.organizationId(), request.jobPositionId(), startedAt)
                .orElseThrow(() -> DosimetryRuleException.invalid("No effective limit exists for the job position"));
        var session = sessions.saveAndFlush(ExposureSession.open(device.organizationId(), request.workerId(),
                request.deviceId(), limit, startedAt));
        // positionId, accumulatedIndex y limitIndex se añadieron el 2026-11-12:
        // el inbox exposure-snapshot de AI Forecasting los exige para crear el
        // modelo de lectura de la sesión, y sin ellos la apertura no se podía
        // entregar. Los tres ya estaban a mano aquí. Mismos nombres que
        // ExposureSessionClosed, salvo positionId, que allí no viaja.
        emit(session.getOrganizationId(), "ExposureSessionOpened",
                Map.of("sessionId", session.getId(), "workerId", session.getWorkerId(),
                        "deviceId", session.getDeviceId(), "limitVersionId", session.getLimitVersionId(),
                        "positionId", session.getJobPositionId(),
                        "accumulatedIndex", session.getAccumulatedIndex(),
                        "limitIndex", session.getLimitIndex(),
                        "startedAt", session.getStartedAt(), "openedBy", actor.userId()), now);
        return view(session);
    }

    public DosimetryResources.Ingestion ingest(DosimetryActor actor, String sessionId, IngestReading request) {
        var session = locked(sessionId);
        actor.requireSupervisor(session.getOrganizationId());
        if (!"uT".equals(request.unit())) throw DosimetryRuleException.invalid("Only uT measurements are supported");
        var candidate = new PersonalReading(session, request.deviceId(), request.bootId(), request.sequence(),
                request.fieldMicrotesla(), request.capturedFrom(), request.capturedTo(), now());
        var previous = readings.findByOrganizationIdAndDeviceIdAndBootIdAndSequence(
                session.getOrganizationId(), candidate.getDeviceId(), candidate.getBootId(), candidate.getSequence());
        if (previous.isPresent()) {
            if (!previous.get().sameCapture(candidate)) {
                throw DosimetryRuleException.conflict("Reading identity was already used for a different capture");
            }
            return new DosimetryResources.Ingestion(DosimetryResources.Reading.from(previous.get()), true, view(session));
        }
        if (candidate.getStatus() == PersonalReading.Status.ACCEPTED
                && readings.existsBySessionIdAndStatusAndCapturedFromLessThanAndCapturedToGreaterThan(
                        sessionId, PersonalReading.Status.ACCEPTED, candidate.getCapturedTo(), candidate.getCapturedFrom())) {
            throw DosimetryRuleException.conflict("Capture interval overlaps an accepted reading");
        }
        if (Boolean.TRUE.equals(request.saturated())) {
            candidate.markSaturated();
            session.recordOutOfRangeReading();
        }
        var reading = readings.saveAndFlush(candidate);
        if (reading.isSaturated() && reading.getStatus() == PersonalReading.Status.LATE) {
            sessions.saveAndFlush(session);
        }
        if (reading.getStatus() == PersonalReading.Status.LATE) {
            emit(session.getOrganizationId(), "PersonalReadingArrivedLate",
                    Map.of("sessionId", sessionId, "readingId", reading.getId(),
                            "capturedAt", reading.getCapturedTo(), "receivedAt", reading.getReceivedAt()), now());
        } else {
            var crossed = session.apply(ExposureIndex.contribution(reading.getFieldMicrotesla(),
                            reading.getCapturedFrom(), reading.getCapturedTo()),
                    ExposureIndex.durationMicros(reading.getCapturedFrom(), reading.getCapturedTo()),
                    reading.getCapturedTo());
            sessions.saveAndFlush(session);
            var payload = Map.<String, Object>of(
                    "sessionId", sessionId, "workerId", session.getWorkerId(), "readingId", reading.getId(),
                    "accumulatedIndex", session.getAccumulatedIndex(), "limitIndex", session.getLimitIndex(),
                    "limitVersionId", session.getLimitVersionId(), "capturedAt", reading.getCapturedTo(),
                    "detectedAt", now(), "methodId", ExposureIndex.METHOD, "unit", ExposureIndex.UNIT);
            if (crossed.warning()) emit(session.getOrganizationId(), "ExposureWarningRaised", payload, reading.getCapturedTo());
            if (crossed.exceeded()) emit(session.getOrganizationId(), "OccupationalLimitExceeded", payload, reading.getCapturedTo());
        }
        return new DosimetryResources.Ingestion(DosimetryResources.Reading.from(reading), false, view(session));
    }

    /** Jornada del dosÃ­metro que cubre la hora de captura, para enrutar lecturas que llegan sin sesiÃ³n. */
    @Transactional(readOnly = true)
    public java.util.Optional<String> sessionCoveringDevice(Long deviceId, Instant at) {
        return sessions.findSessionIdsCovering(deviceId, at, PageRequest.of(0, 1)).stream().findFirst();
    }

    public DosimetryResources.Session requestClose(DosimetryActor actor, String id, CloseSession request) {
        var session = locked(id);
        actor.requireSupervisor(session.getOrganizationId());
        Instant end = request == null ? null : request.endedAt();
        if (session.getState() != ExposureSession.State.OPEN) {
            if (end != null && !end.equals(session.getEndedAt())) {
                throw DosimetryRuleException.conflict("Session end time is immutable");
            }
            return view(session);
        }
        session.requestClose(end == null ? now() : end, now());
        sessions.saveAndFlush(session);
        emit(session.getOrganizationId(), "ExposureSessionCloseRequested",
                Map.of("sessionId", id, "endedAt", session.getEndedAt(), "requestedBy", actor.userId()), now());
        return view(session);
    }

    public DosimetryResources.Session finalizeSession(DosimetryActor actor, String id, FinalizeSession request) {
        var session = locked(id);
        actor.requireSupervisor(session.getOrganizationId());
        if (session.getState() == ExposureSession.State.CLOSED) return view(session);
        session.finalizeSession(now(), request != null && request.deliveryConfirmed());
        sessions.saveAndFlush(session);
        // Map.ofEntries y no Map.of: son once pares y Map.of admite diez.
        // unit, limitIndex, methodId y finalizedAt se añadieron el 2026-11-12
        // porque el inbox de Occupational Health los exige para dejar constancia
        // en un reporte de cumplimiento, y sin ellos ese hecho no se podía
        // entregar. Son los mismos nombres que ya usa OccupationalLimitExceeded.
        emit(session.getOrganizationId(), "ExposureSessionClosed",
                Map.ofEntries(Map.entry("sessionId", id),
                        Map.entry("workerId", session.getWorkerId()),
                        Map.entry("accumulatedIndex", session.getAccumulatedIndex()),
                        Map.entry("limitIndex", session.getLimitIndex()),
                        Map.entry("limitVersionId", session.getLimitVersionId()),
                        Map.entry("coveragePercentage", session.coverage(now())),
                        Map.entry("coverageComplete", session.coverageComplete()),
                        Map.entry("finalizedBy", actor.userId()),
                        Map.entry("finalizedAt", now()),
                        Map.entry("methodId", ExposureIndex.METHOD),
                        Map.entry("unit", ExposureIndex.UNIT)), now());
        return view(session);
    }

    @Transactional(readOnly = true)
    public DosimetryResources.Session getSession(DosimetryActor actor, String id) {
        var session = sessions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Exposure session", id));
        actor.requireRead(session.getOrganizationId(), session.getWorkerId());
        return view(session);
    }

    @Transactional(readOnly = true)
    public DosimetryResources.Session currentExposure(DosimetryActor actor) {
        var session = sessions.findFirstByWorkerIdOrderByStartedAtDesc(actor.userId())
                .orElseThrow(() -> new ResourceNotFoundException("No exposure session exists for this account"));
        actor.requireRead(session.getOrganizationId(), session.getWorkerId());
        return view(session);
    }

    @Transactional(readOnly = true)
    public PageResponse<DosimetryResources.Session> history(DosimetryActor actor, Long workerId, int page, int size) {
        var member = memberships.findById(workerId)
                .orElseThrow(() -> new AccessDeniedException("Worker is outside the authorized scope"));
        actor.requireRead(member.getOrganizationId(), workerId);
        return PageResponse.of(sessions.findByOrganizationIdAndWorkerIdOrderByStartedAtDesc(
                member.getOrganizationId(), workerId, page(page, size)).map(this::view));
    }

    @Transactional(readOnly = true)
    public PageResponse<DosimetryResources.Reading> listReadings(DosimetryActor actor, String id, int page, int size) {
        getSession(actor, id);
        return PageResponse.of(readings.findBySessionIdOrderByCapturedFromAsc(id, page(page, size))
                .map(DosimetryResources.Reading::from));
    }

    @Transactional(readOnly = true)
    public PageResponse<DosimetryResources.Event> pendingEvents(DosimetryActor actor, int page, int size) {
        actor.requireAdministrator();
        return PageResponse.of(events.findByPublishedAtIsNullOrderByOccurredAtAsc(page(page, size)).map(e -> {
            try {
                return new DosimetryResources.Event(e.getId(), e.getEventType(), e.getOrganizationId(),
                        json.readTree(e.getPayload()), e.getOccurredAt());
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Invalid stored outbox payload", ex);
            }
        }));
    }

    public void acknowledgeEvent(DosimetryActor actor, String id) {
        actor.requireAdministrator();
        var event = events.findById(id).orElseThrow(() -> new ResourceNotFoundException("Dosimetry event", id));
        event.acknowledge(now());
        events.save(event);
    }

    private ExposureSession locked(String id) {
        return sessions.findLocked(id).orElseThrow(() -> new ResourceNotFoundException("Exposure session", id));
    }

    private DosimetryResources.Session view(ExposureSession session) {
        return DosimetryResources.Session.from(session, now());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw DosimetryRuleException.invalid("Page must be non-negative and size between 1 and 200");
        return PageRequest.of(page, size);
    }

    private void emit(Long organizationId, String type, Map<String, Object> fields, Instant occurredAt) {
        var payload = new LinkedHashMap<>(fields);
        payload.put("schemaVersion", 1);
        payload.put("organizationId", organizationId);
        try {
            events.saveAndFlush(new DosimetryEvent(organizationId, type, json.writeValueAsString(payload), occurredAt));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize dosimetry event", ex);
        }
    }
}
