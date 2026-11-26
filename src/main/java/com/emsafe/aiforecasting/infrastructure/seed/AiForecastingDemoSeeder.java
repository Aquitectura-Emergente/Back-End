package com.emsafe.aiforecasting.infrastructure.seed;

import com.emsafe.aiforecasting.application.CloseEndedSessionAssessmentsHandler;
import com.emsafe.aiforecasting.application.TemplateOnlyRedaction;
import com.emsafe.aiforecasting.infrastructure.AiForecastingConfiguration;
import com.emsafe.aiforecasting.infrastructure.seed.DemoExposurePlan.PlannedSession;
import com.emsafe.aiforecasting.infrastructure.seed.DemoExposurePlan.Worker;
import com.emsafe.alerts.application.AlarmService;
import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import com.emsafe.devicemanagement.application.DeviceCatalog;
import com.emsafe.devicemanagement.application.DeviceService;
import com.emsafe.devicemanagement.domain.model.Device;
import com.emsafe.devicemanagement.interfaces.rest.dto.CreateDeviceRequest;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.personaldosimetry.application.DosimetryActor;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade;
import com.emsafe.personaldosimetry.application.PersonalDosimetryService;
import com.emsafe.personaldosimetry.domain.model.MembershipRole;
import com.emsafe.personaldosimetry.infrastructure.DosimetryOutboxRelay;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.CloseSession;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.ConfigureLimit;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.FinalizeSession;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.GrantMembership;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.IngestReading;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryRequests.OpenSession;
import com.emsafe.personaldosimetry.interfaces.rest.dto.DosimetryResources;
import com.emsafe.plantconfiguration.application.JobPositionService;
import com.emsafe.plantconfiguration.application.PlantZoneService;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionDto;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionRequest;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantZoneDto;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantZoneRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Datos de demostración de la analítica, apagado por defecto
 * ({@code emsafe.ai-forecasting.seed-demo=false}). Ejecuta {@link DemoExposurePlan} sobre la
 * empresa Quantum ({@value DemoExposurePlan#COMPANY_EMAIL}).
 *
 * <p><b>Nunca escribe en tablas de otro contexto.</b> Todo pasa por sus servicios públicos, con las
 * mismas reglas que la API:</p>
 * <ul>
 *   <li>IAM: crea, si faltan, las cuentas WORKER demo de Quantum (es lo único que se guarda con el
 *       repositorio, igual que DataInitializer y el seeder de Device Management).</li>
 *   <li>Plant Configuration ({@link PlantZoneService}, {@link JobPositionService}): una zona y 3 puestos.</li>
 *   <li>Device Management ({@link DeviceService}, {@link DeviceCatalog}): un dosímetro personal por operario.</li>
 *   <li>Personal Dosimetry ({@link PersonalDosimetryService}, {@link ExposureQueryFacade}): membresías
 *       OPERATOR de los operarios y SSO del responsable de Quantum, límites, 60 jornadas cerradas y una
 *       abierta por operario.</li>
 * </ul>
 *
 * <p>AI Forecasting y Occupational Health reciben los hechos por el relay de Personal Dosimetry, como
 * con datos reales: snapshots, avisos (evaluaciones y alertas), una excedencia (alerta y propuesta de
 * mitigación) y los cierres. Las alertas de las jornadas del historial se dejan acusadas y resueltas
 * enseguida (Alerts: API pública), para que no aparezcan como actuales ni se escalen solas; las 2
 * de las jornadas abiertas hoy quedan pendientes.</p>
 *
 * <p><b>Idempotente.</b> Si los operarios demo ya tienen jornadas cerradas en la ventana, el historial
 * no se repite. Una jornada abierta de más de 8 h (de un arranque anterior) se cierra al cumplir su
 * turno y se abre la de hoy. Cada paso que falla se registra y no detiene a los demás.</p>
 */
@Component
@ConditionalOnProperty(prefix = "emsafe.ai-forecasting", name = "seed-demo", havingValue = "true")
public class AiForecastingDemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(AiForecastingDemoSeeder.class);

    /** Lo que hizo una pasada. */
    record SeedReport(int workers, int closedSessions, int openSessions, int staleSessionsClosed,
                      int historicalAlertsSettled, int endedAssessmentsClosed) {
        static final SeedReport SKIPPED = new SeedReport(0, 0, 0, 0, 0, 0);
    }

    /** Tope de pasadas al drenar el relay de Personal Dosimetry (50 eventos por pasada). */
    static final int MAX_RELAY_PASSES = 20;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PlantZoneService zones;
    private final JobPositionService positions;
    private final DeviceService deviceService;
    private final DeviceCatalog devices;
    private final PersonalDosimetryService dosimetry;
    private final ExposureQueryFacade exposures;
    /** No existe como bean si {@code emsafe.personal-dosimetry.outbox-relay-enabled=false}. */
    private final ObjectProvider<DosimetryOutboxRelay> dosimetryRelay;
    private final AlarmService alarms;
    private final CloseEndedSessionAssessmentsHandler endedAssessments;
    private final Clock clock;

    public AiForecastingDemoSeeder(UserRepository users, PasswordEncoder passwordEncoder, PlantZoneService zones,
                                   JobPositionService positions, DeviceService deviceService, DeviceCatalog devices,
                                   PersonalDosimetryService dosimetry, ExposureQueryFacade exposures,
                                   ObjectProvider<DosimetryOutboxRelay> dosimetryRelay, AlarmService alarms,
                                   CloseEndedSessionAssessmentsHandler endedAssessments,
                                   @Qualifier(AiForecastingConfiguration.CLOCK) Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.zones = zones;
        this.positions = positions;
        this.deviceService = deviceService;
        this.devices = devices;
        this.dosimetry = dosimetry;
        this.exposures = exposures;
        this.dosimetryRelay = dosimetryRelay;
        this.alarms = alarms;
        this.endedAssessments = endedAssessments;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            SeedReport report = seed();
            log.info("AI Forecasting demo seed: {} worker(s), {} closed session(s), {} open session(s), "
                    + "{} stale session(s) closed, {} historical alert(s) acknowledged and resolved, "
                    + "{} ended-session assessment(s) closed",
                    report.workers(), report.closedSessions(), report.openSessions(),
                    report.staleSessionsClosed(), report.historicalAlertsSettled(),
                    report.endedAssessmentsClosed());
        } catch (RuntimeException e) {
            log.warn("AI Forecasting demo seed failed: {}", e.toString());
        }
    }

    SeedReport seed() {
        AppUser company = users.findByEmail(DemoExposurePlan.COMPANY_EMAIL)
                .filter(u -> u.hasRole(Role.CLIENT) && "company".equalsIgnoreCase(u.getClientType()))
                .orElse(null);
        AppUser admin = users.findByEmail(DemoExposurePlan.ADMIN_EMAIL).filter(u -> u.hasRole(Role.ADMIN))
                .orElse(null);
        if (company == null || admin == null) {
            log.info("AI Forecasting demo seed skipped: {} or {} not found",
                    DemoExposurePlan.COMPANY_EMAIL, DemoExposurePlan.ADMIN_EMAIL);
            return SeedReport.SKIPPED;
        }
        Long org = company.getId();
        Instant now = clock.instant();
        DosimetryActor actor = new DosimetryActor(admin.getId(), true, null, null);

        grantSsoMembership(actor, org);
        List<Long> positionIds = ensurePositions(org);
        ensureLimits(actor, org, positionIds, DemoExposurePlan.historyStart(now).minus(Duration.ofDays(1)));
        List<Participant> participants = ensureParticipants(actor, org, positionIds);

        int stale = 0;
        Set<Integer> busy = new HashSet<>();
        for (Participant p : participants) {
            switch (closeStaleSession(actor, org, p, now)) {
                case CLOSED -> stale++;
                case STILL_OPEN -> busy.add(p.index());
                case NONE -> { }
            }
        }
        List<String> history = historyAlreadySeeded(org, participants, now)
                ? List.of() : seedHistory(actor, participants, busy, now);
        int settled = history.isEmpty() ? 0 : settleHistoricalAlerts(participants, Set.copyOf(history));
        // Las evaluaciones que dejan los avisos del historial son de jornadas ya cerradas: se cierran
        // con SESSION_ENDED ahora, sin esperar a la tarea programada.
        int endedClosed = closeEndedAssessments(org);
        // Las de hoy se abren después del drenaje: sus avisos los entrega el relay programado y
        // quedan pendientes, que es lo correcto porque esas jornadas siguen abiertas.
        int open = 0;
        for (Participant p : participants) {
            if (!busy.contains(p.index()) && openToday(actor, p, now)) {
                open++;
            }
        }
        return new SeedReport(participants.size(), history.size(), open, stale, settled, endedClosed);
    }

    // ─── Preparación ─────────────────────────────────────────────────────────

    /** Un operario del guion listo para trabajar: cuenta, dosímetro y puesto resueltos. */
    record Participant(int index, String email, Long workerId, Long deviceId, Long positionId) {
    }

    /**
     * Membresía SSO de Personal Dosimetry para el SSO_RESPONSIBLE de Quantum, para que desde el portal
     * pueda abrir, cerrar y consultar jornadas de su empresa. Idempotente: concederla otra vez con la
     * misma organización y rol no hace nada. No crea la cuenta (es de DataInitializer). En producción
     * la concede un ADMIN con {@code POST /api/v1/personal-dosimetry/memberships}.
     */
    private void grantSsoMembership(DosimetryActor actor, Long org) {
        AppUser sso = users.findByEmail(DemoExposurePlan.SSO_EMAIL).orElse(null);
        if (sso == null || !sso.hasRole(Role.SSO_RESPONSIBLE) || !sso.belongsTo(org) || !sso.isActive()) {
            log.warn("AI Forecasting demo seed: {} is not an active SSO_RESPONSIBLE of organization {}; "
                    + "no SSO dosimetry membership granted", DemoExposurePlan.SSO_EMAIL, org);
            return;
        }
        try {
            dosimetry.grantMembership(actor, new GrantMembership(sso.getId(), org, MembershipRole.SSO));
        } catch (RuntimeException e) {
            log.warn("AI Forecasting demo seed: SSO dosimetry membership for {} not granted: {}",
                    DemoExposurePlan.SSO_EMAIL, e.toString());
        }
    }

    private List<Long> ensurePositions(Long org) {
        Long zoneId = zones.listFor(org).stream()
                .filter(z -> DemoExposurePlan.PLANT_ZONE.equals(z.name())).map(PlantZoneDto::id).findFirst()
                .orElseGet(() -> zones.create(org, new PlantZoneRequest(DemoExposurePlan.PLANT_ZONE,
                        "Zona de la demo de analítica de AI Forecasting")).id());
        List<JobPositionDto> existing = positions.listFor(org, zoneId);
        List<Long> ids = new ArrayList<>();
        for (String name : DemoExposurePlan.POSITIONS) {
            ids.add(existing.stream().filter(p -> name.equals(p.name())).map(JobPositionDto::id).findFirst()
                    .orElseGet(() -> positions.create(org, new JobPositionRequest(zoneId, name, null)).id()));
        }
        return ids;
    }

    /** Un límite vigente desde antes del historial; si ya hay uno así para el puesto, no se toca. */
    private void ensureLimits(DosimetryActor actor, Long org, List<Long> positionIds, Instant effectiveFrom) {
        List<DosimetryResources.Limit> limits = dosimetry.listLimits(actor, org, 0, 200).content();
        for (Long positionId : positionIds) {
            boolean covered = limits.stream().anyMatch(l -> positionId.equals(l.jobPositionId())
                    && !l.effectiveFrom().isAfter(effectiveFrom));
            if (!covered) {
                dosimetry.configureLimit(actor, org, new ConfigureLimit(positionId, DemoExposurePlan.LIMIT_INDEX,
                        DemoExposurePlan.WARNING_RATIO, effectiveFrom, DemoExposurePlan.LIMIT_REFERENCE));
            }
        }
    }

    private List<Participant> ensureParticipants(DosimetryActor actor, Long org, List<Long> positionIds) {
        List<Participant> participants = new ArrayList<>();
        for (int i = 0; i < DemoExposurePlan.WORKERS.size(); i++) {
            Worker w = DemoExposurePlan.WORKERS.get(i);
            try {
                AppUser worker = ensureWorker(w, org).orElse(null);
                Long deviceId = worker == null ? null : ensureDosimeter(w, org).orElse(null);
                if (worker == null || deviceId == null) {
                    continue;
                }
                dosimetry.grantMembership(actor, new GrantMembership(worker.getId(), org, MembershipRole.OPERATOR));
                participants.add(new Participant(i, w.email(), worker.getId(), deviceId,
                        positionIds.get(w.position())));
            } catch (RuntimeException e) {
                log.warn("AI Forecasting demo seed: skipping {}: {}", w.email(), e.toString());
            }
        }
        return participants;
    }

    /** La cuenta WORKER activa de Quantum; se crea si falta. Una cuenta con ese email de otro tipo no se toca. */
    private Optional<AppUser> ensureWorker(Worker w, Long org) {
        Optional<AppUser> existing = users.findByEmail(w.email());
        if (existing.isPresent()) {
            AppUser u = existing.get();
            if (!u.hasRole(Role.WORKER) || !u.belongsTo(org) || !u.isActive()) {
                log.warn("AI Forecasting demo seed: {} exists but is not an active WORKER of organization {}",
                        w.email(), org);
                return Optional.empty();
            }
            return existing;
        }
        return Optional.of(users.save(AppUser.builder().email(w.email())
                .passwordHash(passwordEncoder.encode(DemoExposurePlan.WORKER_PASSWORD))
                .name(w.name()).initials(w.initials()).role(Role.WORKER).status("active").organizationId(org)
                .joinDate(LocalDate.now(clock)).build()));
    }

    /** El dosímetro personal del operario, registrado en Device Management a nombre de la empresa. */
    private Optional<Long> ensureDosimeter(Worker w, Long org) {
        Device existing = devices.findBySerialNumber(w.dosimeterSerial());
        if (existing != null) {
            Long owner = existing.getClient() == null ? null : existing.getClient().getId();
            if (!org.equals(owner) || !existing.isActive()) {
                log.warn("AI Forecasting demo seed: dosimeter {} is not an active device of organization {}",
                        w.dosimeterSerial(), org);
                return Optional.empty();
            }
            return Optional.of(existing.getId());
        }
        return Optional.of(deviceService.create(new CreateDeviceRequest("Dosímetro demo " + w.initials(),
                "Dosímetro", DemoExposurePlan.PLANT_ZONE, "active", w.dosimeterSerial(), null, org,
                "PERSONAL_DOSIMETER")).id());
    }

    // ─── Jornadas ────────────────────────────────────────────────────────────

    private enum StaleOutcome { NONE, CLOSED, STILL_OPEN }

    /** Cierra la jornada abierta de un arranque anterior si ya cumplió su turno. */
    private StaleOutcome closeStaleSession(DosimetryActor actor, Long org, Participant p, Instant now) {
        Optional<ExposureQueryFacade.CurrentExposure> current = exposures.currentExposure(org, p.workerId());
        if (current.isEmpty()) {
            return StaleOutcome.NONE;
        }
        try {
            DosimetryResources.Session session = dosimetry.getSession(actor, current.get().sessionId());
            Instant shiftEnd = session.startedAt().plus(DemoExposurePlan.SHIFT);
            if (shiftEnd.isAfter(now)) {
                return StaleOutcome.STILL_OPEN;
            }
            Instant latest = current.get().latestCaptureTo();
            Instant end = latest != null && latest.isAfter(shiftEnd) ? latest : shiftEnd;
            dosimetry.requestClose(actor, session.id(), new CloseSession(end));
            dosimetry.finalizeSession(actor, session.id(), new FinalizeSession(true));
            return StaleOutcome.CLOSED;
        } catch (RuntimeException e) {
            log.warn("AI Forecasting demo seed: could not close open session of worker {}: {}", p.workerId(),
                    e.toString());
            return StaleOutcome.STILL_OPEN;
        }
    }

    private boolean historyAlreadySeeded(Long org, List<Participant> participants, Instant now) {
        Set<Long> workerIds = new HashSet<>();
        participants.forEach(p -> workerIds.add(p.workerId()));
        return exposures.closedSessions(org, DemoExposurePlan.historyStart(now), now.plusSeconds(1)).stream()
                .anyMatch(s -> workerIds.contains(s.workerId()));
    }

    /** @return ids de las jornadas del historial creadas en esta pasada */
    private List<String> seedHistory(DosimetryActor actor, List<Participant> participants, Set<Integer> busy,
                                     Instant now) {
        List<String> created = new ArrayList<>();
        for (PlannedSession planned : DemoExposurePlan.history(now)) {
            Participant p = participants.stream().filter(x -> x.index() == planned.worker()).findFirst()
                    .orElse(null);
            if (p == null || busy.contains(p.index())) {
                continue;
            }
            try {
                String id = start(actor, p, planned);
                dosimetry.requestClose(actor, id, new CloseSession(planned.end()));
                dosimetry.finalizeSession(actor, id, new FinalizeSession(true));
                created.add(id);
            } catch (RuntimeException e) {
                log.warn("AI Forecasting demo seed: session of worker {} on {} not created: {}", p.workerId(),
                        planned.day(), e.toString());
            }
        }
        return created;
    }

    // ─── Alertas del historial ───────────────────────────────────────────────

    /**
     * Las jornadas del historial generan avisos que Occupational Health convierte en alertas con
     * fecha de hoy. Sin esto quedarían pendientes como si fueran actuales y la excedencia se
     * escalaría sola al SSO a los 60 s. Se drena el relay de Personal Dosimetry para que existan ya,
     * y cada una se acusa como su operario y se resuelve, antes de que corra el escalamiento. Solo
     * API públicas de Alerts ({@link AlarmService#forWorker}, {@link AlarmService#acknowledgeAs},
     * {@link AlarmService#resolve}).
     *
     * @return alertas del historial acusadas y resueltas
     */
    private int settleHistoricalAlerts(List<Participant> participants, Set<String> historySessionIds) {
        DosimetryOutboxRelay relay = dosimetryRelay.getIfAvailable();
        if (relay == null) {
            log.warn("AI Forecasting demo seed: the Personal Dosimetry outbox relay is disabled "
                    + "(emsafe.personal-dosimetry.outbox-relay-enabled=false); historical alerts were not settled");
            return 0;
        }
        // Evaluaciones del historial: plantilla, sin llamar al LLM (arranque rápido y sin gastar cuota).
        TemplateOnlyRedaction.run(() -> drain(relay));
        int settled = 0;
        for (Participant p : participants) {
            for (AlertDto alert : alarms.forWorker(p.email())) {
                if (alert.sessionId() == null || !historySessionIds.contains(alert.sessionId())
                        || Boolean.TRUE.equals(alert.resolved())) {
                    continue;
                }
                try {
                    if (alert.acknowledgedAt() == null) {
                        alarms.acknowledgeAs(alert.id(), p.email());
                    }
                    alarms.resolve(alert.id());
                    settled++;
                } catch (RuntimeException e) {
                    log.warn("AI Forecasting demo seed: could not settle alert {}: {}", alert.id(), e.toString());
                }
            }
        }
        return settled;
    }

    private int closeEndedAssessments(Long org) {
        try {
            return endedAssessments.closeEnded(org);
        } catch (RuntimeException e) {
            log.warn("AI Forecasting demo seed: could not close ended-session assessments: {}", e.toString());
            return 0;
        }
    }

    /** Pasadas hasta que una no entregue nada (los avisos que llegan antes que su apertura van en la siguiente). */
    private static void drain(DosimetryOutboxRelay relay) {
        for (int pass = 1; pass <= MAX_RELAY_PASSES; pass++) {
            if (relay.drainOnce() == 0) {
                return;
            }
        }
        log.warn("AI Forecasting demo seed: Personal Dosimetry relay still had events after {} passes; "
                + "the scheduled relay will deliver the rest", MAX_RELAY_PASSES);
    }

    private boolean openToday(DosimetryActor actor, Participant p, Instant now) {
        try {
            start(actor, p, DemoExposurePlan.open(p.index(), now));
            return true;
        } catch (RuntimeException e) {
            log.warn("AI Forecasting demo seed: open session of worker {} not created: {}", p.workerId(),
                    e.toString());
            return false;
        }
    }

    /** Abre la jornada y le carga su única captura. */
    private String start(DosimetryActor actor, Participant p, PlannedSession planned) {
        String id = dosimetry.openSession(actor,
                new OpenSession(p.workerId(), p.deviceId(), p.positionId(), planned.start())).id();
        dosimetry.ingest(actor, id, new IngestReading(p.deviceId(), planned.bootId(), 1L,
                planned.fieldMicrotesla(), "uT", planned.start(), planned.readingTo()));
        return id;
    }
}
