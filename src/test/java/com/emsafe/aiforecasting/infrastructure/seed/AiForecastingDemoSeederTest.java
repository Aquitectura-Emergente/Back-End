package com.emsafe.aiforecasting.infrastructure.seed;

import com.emsafe.aiforecasting.application.AnalyticsSummaryQueryHandler;
import com.emsafe.aiforecasting.application.CloseEndedSessionAssessmentsHandler;
import com.emsafe.aiforecasting.application.CriticalWorkersQueryHandler;
import com.emsafe.aiforecasting.domain.model.AssessmentStatus;
import com.emsafe.aiforecasting.domain.model.CloseReason;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.AssessmentSearchCriteria;
import com.emsafe.aiforecasting.infrastructure.SessionEndReconciliationJob;
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
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La semilla de demo de punta a punta, con seed-demo=true: corre al arrancar sobre los datos de
 * DataInitializer, pasa por los servicios de IAM, Plant Configuration, Device Management y Personal
 * Dosimetry, y después el relay de Personal Dosimetry alimenta a AI Forecasting como en producción.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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
    @Autowired private MockMvc mvc;
    @Autowired private CriticalWorkersQueryHandler criticalWorkers;
    @Autowired private AssessmentRepository assessments;
    @Autowired private CloseEndedSessionAssessmentsHandler endedHandler;
    /** Se construye a mano: el bean está apagado en el perfil de test (session-end-reconcile-enabled=false). */
    private SessionEndReconciliationJob reconciliation;

    @BeforeEach
    void buildReconciliation() {
        reconciliation = new SessionEndReconciliationJob(endedHandler);
    }

    private static final String PD = "/api/v1/personal-dosimetry";

    private String ssoToken() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + DemoExposurePlan.SSO_EMAIL + "\",\"password\":\"sso12345\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

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

    /** "Análisis con IA" sin LLM (perfil de test): el fallback cubre toda la estructura y cuadra con el resumen. */
    @Test
    void theFallbackInsightsOfTheDemoAreCompleteAndMatchTheSummary() throws Exception {
        drainRelay();
        long org = org();
        List<Long> workers = demoWorkerIds();
        LocalDate from = LocalDate.now(DemoExposurePlan.ZONE).minusDays(21);
        AnalyticsSummary s = analytics.summarize(org, from, null, null);

        String body = mvc.perform(as(ssoToken(), post("/api/v1/ai-forecasting/analytics/organizations/" + org
                                + "/insights").param("from", from.toString()).param("refresh", "true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generatedBy").value("FALLBACK"))
                .andExpect(jsonPath("$.data.model").doesNotExist())
                .andExpect(jsonPath("$.data.isAdvisory").value(true))
                .andExpect(jsonPath("$.data.disclaimer").value(startsWith(
                        "Análisis generado con reglas automáticas (IA no disponible).")))
                .andExpect(jsonPath("$.data.period.from").value(s.from().toString()))
                .andExpect(jsonPath("$.data.period.to").value(s.to().toString()))
                .andExpect(jsonPath("$.data.period.zone").value("America/Lima"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        String resumen = JsonPath.read(body, "$.data.resumen");
        assertThat(resumen).contains("60 jornadas de 4 operarios")
                .contains(s.totals().sessionsNearLimit() + " (" + s.totals().sessionsNearLimitPct() + " %)")
                .contains(s.totals().sessionsExceeded() + " (" + s.totals().sessionsExceededPct() + " %)")
                .contains("2 operarios con proyección HIGH o CRITICAL").contains("1 CRITICAL");

        List<String> levels = JsonPath.read(body, "$.data.hallazgos[*].nivel");
        List<String> findings = JsonPath.read(body, "$.data.hallazgos[*].texto");
        assertThat(levels).first().isEqualTo("CRITICO");
        assertThat(levels).isSortedAccordingTo(java.util.Comparator.comparingInt(
                l -> List.of("CRITICO", "ATENCION", "INFO").indexOf(l)));
        // los textos nombran a los operarios con su nombre de IAM; operariosAfectados sigue con los IDs
        String name0 = users.findById(workers.get(0)).orElseThrow().getName();
        String name1 = users.findById(workers.get(1)).orElseThrow().getName();
        assertThat(findings.get(0)).contains("1 jornada superó el límite").endsWith(": " + name0 + ".");
        assertThat(findings).anySatisfy(f -> assertThat(f).contains("CRITICAL").contains("de " + name1));
        assertThat(findings).anySatisfy(f -> assertThat(f).contains("HIGH").contains("de " + name0));
        assertThat(body).doesNotContain("operario " + workers.get(0)).doesNotContain("operario #");
        List<String> actions = JsonPath.read(body, "$.data.recomendaciones[*].accion");
        assertThat(actions.get(0)).contains(name1);
        assertThat(findings).anySatisfy(f -> assertThat(f).contains("Prensa hidráulica"));

        List<String> priorities = JsonPath.read(body, "$.data.recomendaciones[*].prioridad");
        assertThat(priorities).first().isEqualTo("ALTA");
        assertThat(priorities).isSortedAccordingTo(java.util.Comparator.comparingInt(
                p -> List.of("ALTA", "MEDIA", "BAJA").indexOf(p)));
        // una acción por cada proyección actual, con el operario afectado
        List<Integer> firstAffected = JsonPath.read(body, "$.data.recomendaciones[0].operariosAfectados");
        assertThat(firstAffected.get(0).longValue()).isEqualTo(workers.get(1));
        List<String> positionNames = JsonPath.read(body, "$.data.recomendaciones[*].puestosAfectados[*].positionName");
        assertThat(positionNames).contains("Prensa hidráulica");
        assertThat(JsonPath.<String>read(body, "$.data.calidadDatos")).contains("1 de 60 jornadas tiene cobertura incompleta");

        // nada cambió: las mismas proyecciones antes y después
        assertThat(analytics.summarize(org, from, null, null).projectedCriticalWorkers())
                .isEqualTo(s.projectedCriticalWorkers());
    }

    /**
     * Cada aviso del historial dejaba una evaluación activa para siempre (una por jornada), y
     * critical-workers devolvía al mismo operario varias veces. Ahora: solo las 2 de hoy, sin duplicados,
     * y las del historial quedan cerradas con SESSION_ENDED.
     */
    @Test
    void criticalWorkersShowsOnlyTodaysTwoAndHistoricalAssessmentsAreClosedWithSessionEnded() throws Exception {
        drainRelay();
        long org = org();
        List<Long> workers = demoWorkerIds();
        Set<String> historySessions = demoHistory().stream().map(ClosedSession::sessionId)
                .collect(Collectors.toSet());

        List<PreventiveAssessment> critical = criticalWorkers.list(org, null, 0, 200).content();
        assertThat(critical).extracting(PreventiveAssessment::getWorkerId)
                .containsExactly(workers.get(1), workers.get(0));
        assertThat(critical).extracting(PreventiveAssessment::getCriticality)
                .containsExactly(CriticalityLevel.CRITICAL, CriticalityLevel.HIGH);
        assertThat(critical).extracting(PreventiveAssessment::getSessionId).doesNotContainAnyElementsOf(historySessions);

        // por HTTP, como lo pide el portal del SSO
        String token = ssoToken();
        mvc.perform(as(token, get("/api/v1/ai-forecasting/projections/critical-workers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[*].workerId", contains(
                        workers.get(1).intValue(), workers.get(0).intValue())));
        String todays = critical.get(1).getId();
        mvc.perform(as(token, get("/api/v1/ai-forecasting/projections/workers/" + workers.get(0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(todays));

        // las evaluaciones del historial (una por aviso: 9) quedan cerradas por fin de jornada (las cerró
        // la semilla). La excedencia no genera evaluación: AI Forecasting solo recibe los avisos.
        List<PreventiveAssessment> historical = assessments.search(
                new AssessmentSearchCriteria(org, null, null, null, null), 0, 200).content().stream()
                .filter(a -> historySessions.contains(a.getSessionId())).toList();
        assertThat(historical).hasSize(9).allSatisfy(a -> {
            assertThat(a.getStatus()).isEqualTo(AssessmentStatus.CLOSED);
            assertThat(a.getCloseReason()).isEqualTo(CloseReason.SESSION_ENDED);
        });
        assertThat(reconciliation.reconcileOnce()).isZero(); // ya no queda nada por cerrar
    }

    /**
     * La membresía SSO sembrada deja al responsable de Quantum operar como en el portal: las llamadas
     * de la pantalla /sso/exposure (sus operarios, sus jornadas y lecturas) y abrir y cerrar jornadas.
     * Va al final porque cierra y reabre la jornada de un operario.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void theQuantumSsoCanReadExposureAndOpenAndCloseSessions() throws Exception {
        long org = org();
        String token = ssoToken();
        DemoExposurePlan.Worker w = DemoExposurePlan.WORKERS.get(2); // operario4: sin avisos, sin evaluación
        long workerId = users.findByEmail(w.email()).orElseThrow().getId();
        String seeded = exposures.currentExposure(org, workerId).orElseThrow().sessionId();

        // lo que pide la pantalla /sso/exposure del portal
        mvc.perform(as(token, get("/api/sso/workers"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].email", hasItem(w.email())));
        mvc.perform(as(token, get(PD + "/workers/" + workerId + "/sessions"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id", hasItem(seeded)));
        String detail = mvc.perform(as(token, get(PD + "/sessions/" + seeded))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(as(token, get(PD + "/sessions/" + seeded + "/readings"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        // cerrar la jornada sembrada y abrir otra con el mismo dosímetro y puesto
        mvc.perform(as(token, post(PD + "/sessions/" + seeded + "/close"))).andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.data.state").value("CLOSING"));
        Number deviceId = JsonPath.read(detail, "$.data.deviceId");
        Number positionId = JsonPath.read(detail, "$.data.jobPositionId");
        String opened = mvc.perform(as(token, post(PD + "/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":%d,\"deviceId\":%d,\"jobPositionId\":%d}"
                                .formatted(workerId, deviceId.longValue(), positionId.longValue()))))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.data.state").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        String newId = JsonPath.read(opened, "$.data.id");
        mvc.perform(as(token, post(PD + "/sessions/" + newId + "/close"))).andExpect(status().is2xxSuccessful());

        // conceder otra vez la membresía (otro arranque) no falla ni la cambia
        seeder.seed();
        mvc.perform(as(token, get(PD + "/sessions/" + newId))).andExpect(status().isOk());
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
