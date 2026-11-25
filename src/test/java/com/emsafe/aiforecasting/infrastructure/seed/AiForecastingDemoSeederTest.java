package com.emsafe.aiforecasting.infrastructure.seed;

import com.emsafe.aiforecasting.application.AnalyticsSummaryQueryHandler;
import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.alerts.application.AlarmService;
import com.emsafe.alerts.interfaces.rest.dto.AlertDto;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.occupationalhealth.infrastructure.AlertEscalationJob;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade.ClosedSession;
import com.emsafe.personaldosimetry.infrastructure.DosimetryOutboxRelay;
import com.emsafe.plantconfiguration.application.JobPositionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La semilla de demo de punta a punta, con seed-demo=true: corre al arrancar sobre los datos de
 * DataInitializer, pasa por los servicios de IAM, Plant Configuration, Device Management y Personal
 * Dosimetry, y después el relay de Personal Dosimetry alimenta a AI Forecasting como en producción.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:af_demo_seed;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.flyway.enabled=false",
        "emsafe.ai-forecasting.seed-demo=true",
        // el perfil test apaga el relay; aquí se enciende y se drena a mano (planificador a 1 h)
        "emsafe.personal-dosimetry.outbox-relay-enabled=true",
        "emsafe.personal-dosimetry.outbox-relay-delay-ms=3600000"
})
class AiForecastingDemoSeederTest {

    @Autowired private AiForecastingDemoSeeder seeder;
    @Autowired private UserRepository users;
    @Autowired private ExposureQueryFacade exposures;
    @Autowired private JobPositionService positions;
    @Autowired private DosimetryOutboxRelay relay;
    @Autowired private AnalyticsSummaryQueryHandler analytics;
    @Autowired private AlarmService alarms;
    @Autowired private AlertEscalationJob escalation;

    private long org() {
        return users.findByEmail(DemoExposurePlan.COMPANY_EMAIL).orElseThrow().getId();
    }

    private List<Long> demoWorkerIds() {
        return DemoExposurePlan.WORKERS.stream()
                .map(w -> users.findByEmail(w.email()).orElseThrow().getId()).toList();
    }

    private List<ClosedSession> demoHistory() {
        Instant now = Instant.now();
        Set<Long> ids = Set.copyOf(demoWorkerIds());
        return exposures.closedSessions(org(), DemoExposurePlan.historyStart(now), now.plusSeconds(1)).stream()
                .filter(s -> ids.contains(s.workerId())).toList();
    }

    private void drainRelay() {
        // los avisos pueden adelantarse a la apertura (409 en AI Forecasting) y quedar para la pasada siguiente
        for (int pass = 0; pass < 20 && relay.drainOnce() > 0; pass++) {
            // sigue hasta que no quede nada entregable
        }
    }

    @Test
    void seedsWorkersHistoryAndOpenSessionsThroughThePublicServicesAndIsIdempotent() {
        long org = org();

        // IAM: 4 WORKER activos de Quantum (operario1 venía de DataInitializer)
        assertThat(DemoExposurePlan.WORKERS).allSatisfy(w -> {
            AppUser u = users.findByEmail(w.email()).orElseThrow();
            assertThat(u.getRole()).isEqualTo(Role.WORKER);
            assertThat(u.getOrganizationId()).isEqualTo(org);
            assertThat(u.isActive()).isTrue();
        });

        // Plant Configuration: los 3 puestos de la demo
        assertThat(positions.listFor(org, null)).extracting(p -> p.name())
                .containsAll(DemoExposurePlan.POSITIONS);

        // Personal Dosimetry: 60 jornadas cerradas, una excedida, una incompleta; una abierta por operario
        List<ClosedSession> history = demoHistory();
        assertThat(history).hasSize(60);
        assertThat(history.stream().filter(ClosedSession::limitExceeded)).hasSize(1);
        assertThat(history.stream().filter(ClosedSession::warningRaised)).hasSize(9);
        assertThat(history.stream().filter(s -> !s.coverageComplete())).hasSize(1);
        assertThat(demoWorkerIds()).allSatisfy(id -> assertThat(exposures.currentExposure(org, id)).isPresent());

        // segunda pasada (otro arranque): nada nuevo
        AiForecastingDemoSeeder.SeedReport again = seeder.seed();
        assertThat(again.workers()).isEqualTo(4);
        assertThat(again.closedSessions()).isZero();
        assertThat(again.openSessions()).isZero();
        assertThat(again.staleSessionsClosed()).isZero();
        assertThat(again.historicalAlertsSettled()).isZero();
        assertThat(demoHistory()).hasSize(60);
    }

    @Test
    void afterTheRelayTheAnalyticsSummaryShowsTheDemo() {
        drainRelay();
        long org = org();
        List<Long> workers = demoWorkerIds();

        AnalyticsSummary s = analytics.summarize(org, LocalDate.now(DemoExposurePlan.ZONE).minusDays(21), null, null);

        assertThat(s.totals().closedSessions()).isEqualTo(60);
        assertThat(s.totals().workers()).isEqualTo(4);
        assertThat(s.totals().sessionsAboveWarning()).isEqualTo(9);
        assertThat(s.totals().sessionsNearLimit()).isEqualTo(2);
        assertThat(s.totals().sessionsExceeded()).isEqualTo(1);
        assertThat(s.totals().incompleteCoverageSessions()).isEqualTo(1);
        assertThat(s.perWorker().get(0).workerId()).isEqualTo(workers.get(0));
        assertThat(s.topPositions()).extracting(AnalyticsSummary.PositionStats::positionName)
                .containsExactly("Prensa hidráulica", "Soldadura", "Inspección de calidad");
        assertThat(s.workersAboveWarning()).extracting(AnalyticsSummary.WarningWorker::workerId)
                .containsExactly(workers.get(0), workers.get(1));

        // hoy: operario3 CRITICAL y operario1 HIGH (jornadas abiertas); las evaluaciones del historial,
        // cuyas jornadas ya cerraron, quedan fuera
        Map<Long, CriticalityLevel> critical = s.projectedCriticalWorkers().stream().collect(
                Collectors.toMap(AnalyticsSummary.ProjectedCritical::workerId,
                        AnalyticsSummary.ProjectedCritical::criticality));
        assertThat(critical).containsOnly(
                Map.entry(workers.get(1), CriticalityLevel.CRITICAL),
                Map.entry(workers.get(0), CriticalityLevel.HIGH));
        assertThat(s.projectedCriticalWorkers()).extracting(AnalyticsSummary.ProjectedCritical::workerId)
                .containsExactly(workers.get(1), workers.get(0));
    }

    @Test
    void historicalAlertsAreAcknowledgedAndResolvedAndOnlyTodaysTwoStayPending() {
        drainRelay(); // entrega también los avisos de las jornadas abiertas hoy
        Set<String> historySessions = demoHistory().stream().map(ClosedSession::sessionId)
                .collect(Collectors.toSet());
        List<AlertDto> alerts = DemoExposurePlan.WORKERS.stream()
                .flatMap(w -> alarms.forWorker(w.email()).stream()).toList();

        // 1) historial: 9 avisos + 1 excedencia, todos acusados y resueltos
        List<AlertDto> historical = alerts.stream().filter(a -> historySessions.contains(a.sessionId())).toList();
        assertThat(historical).hasSize(10);
        assertThat(historical.stream().filter(a -> "danger".equals(a.type()))).hasSize(1);
        assertThat(historical).allSatisfy(a -> {
            assertThat(a.acknowledgedAt()).isNotNull();
            assertThat(a.resolved()).isTrue();
            assertThat(a.escalatedAt()).isNull();
        });

        // 2) el escalamiento automático no tiene nada que escalar, ni pasado el minuto de acuse
        assertThat(escalation.escalateOverdue(LocalDateTime.now().plusMinutes(2))).isZero();

        // 3) pendientes: exactamente los 2 avisos de las jornadas abiertas hoy (operario1 y operario3)
        List<Long> workers = demoWorkerIds();
        List<AlertDto> pending = alerts.stream().filter(a -> !Boolean.TRUE.equals(a.resolved())).toList();
        assertThat(pending).hasSize(2).allSatisfy(a -> {
            assertThat(a.type()).isEqualTo("warning");
            assertThat(a.acknowledgedAt()).isNull();
            assertThat(historySessions).doesNotContain(a.sessionId());
        });
        assertThat(pending).extracting(AlertDto::workerId).containsExactlyInAnyOrder(workers.get(0), workers.get(1));
    }
}
