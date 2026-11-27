package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.ClosedExposureSession;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.ClosedSessionHistoryPort;
import com.emsafe.aiforecasting.domain.ports.OpenSessionPort;
import com.emsafe.aiforecasting.domain.ports.WorkerSnapshotRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Qué evaluaciones activas siguen vigentes: las de jornadas que no terminaron en Personal Dosimetry,
 * y como mucho <b>una por operario</b> (la más reciente).
 *
 * <p>Una evaluación es activa por <em>sesión</em> ({@code active_session_key}), no por operario, y este
 * contexto no recibe el cierre de jornada: cada jornada con aviso deja su evaluación activa. Por eso
 * se cruza con Personal Dosimetry por sus fachadas públicas. Una jornada <b>terminó</b> si:</p>
 * <ul>
 *   <li>Personal Dosimetry la tiene {@code CLOSED} ({@link ClosedSessionHistoryPort}), o</li>
 *   <li>el operario tiene <b>otra</b> jornada {@code OPEN} ({@link OpenSessionPort}): un operario solo
 *       tiene una abierta a la vez, así que esta ya cerró o está cerrando.</li>
 * </ul>
 * <p>Una sesión que Personal Dosimetry no conoce (llegada solo por el inbox REST, como en la guía de
 * Swagger) no se da por terminada.</p>
 */
public class OpenSessionAssessments {

    /** Respaldo si una evaluación activa no tiene snapshot (no debería pasar). */
    private static final Duration FALLBACK_LOOKBACK = Duration.ofDays(2);

    private final AssessmentRepository assessments;
    private final WorkerSnapshotRepository snapshots;
    private final ClosedSessionHistoryPort history;
    private final OpenSessionPort openSessions;
    private final Clock clock;

    public OpenSessionAssessments(AssessmentRepository assessments, WorkerSnapshotRepository snapshots,
                                  ClosedSessionHistoryPort history, OpenSessionPort openSessions, Clock clock) {
        this.assessments = assessments;
        this.snapshots = snapshots;
        this.history = history;
        this.openSessions = openSessions;
        this.clock = clock;
    }

    /** Evaluaciones vigentes de la organización: jornadas no terminadas, la más reciente por operario. */
    public List<PreventiveAssessment> current(Long organizationId) {
        List<PreventiveAssessment> active = assessments.findActiveByOrganizationId(organizationId);
        Set<String> ended = endedSessionIds(organizationId, active);
        Map<Long, PreventiveAssessment> latest = new LinkedHashMap<>();
        Comparator<PreventiveAssessment> newer = Comparator.comparing(PreventiveAssessment::getAssessedAt)
                .thenComparing(PreventiveAssessment::getId);
        for (PreventiveAssessment a : active) {
            if (!ended.contains(a.getSessionId())) {
                latest.merge(a.getWorkerId(), a, (x, y) -> newer.compare(x, y) >= 0 ? x : y);
            }
        }
        return List.copyOf(latest.values());
    }

    /** La evaluación vigente del operario, si tiene. */
    public Optional<PreventiveAssessment> currentForWorker(Long workerId) {
        return assessments.findActiveByWorkerId(workerId)
                .flatMap(any -> current(any.getOrganizationId()).stream()
                        .filter(a -> a.getWorkerId().equals(workerId))
                        .findFirst());
    }

    /** Sesiones de {@code active} que Personal Dosimetry ya dio por terminadas. */
    public Set<String> endedSessionIds(Long organizationId, List<PreventiveAssessment> active) {
        if (active.isEmpty()) {
            return Set.of();
        }
        Set<String> activeSessions = active.stream().map(PreventiveAssessment::getSessionId)
                .collect(Collectors.toSet());
        Instant earliest = active.stream()
                .map(a -> snapshots.findBySessionId(a.getSessionId())
                        .map(WorkerExposureSnapshot::sessionStartedAt)
                        .orElse(a.getAssessedAt().minus(FALLBACK_LOOKBACK)))
                .min(Instant::compareTo)
                .orElseThrow();
        Set<String> ended = new HashSet<>();
        history.closedSessions(organizationId, earliest, clock.instant().plusSeconds(1)).stream()
                .map(ClosedExposureSession::sessionId)
                .filter(activeSessions::contains)
                .forEach(ended::add);
        Map<Long, Optional<String>> openByWorker = new HashMap<>();
        for (PreventiveAssessment a : active) {
            Optional<String> open = openByWorker.computeIfAbsent(a.getWorkerId(),
                    w -> openSessions.openSessionId(organizationId, w));
            if (open.isPresent() && !open.get().equals(a.getSessionId())) {
                ended.add(a.getSessionId());
            }
        }
        return ended;
    }
}
