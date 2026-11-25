package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.AnalyticsSummary;
import com.emsafe.aiforecasting.domain.model.ClosedExposureSession;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.AssessmentSearchCriteria;
import com.emsafe.aiforecasting.domain.ports.ClosedSessionHistoryPort;
import com.emsafe.aiforecasting.domain.ports.PageResult;
import com.emsafe.aiforecasting.domain.ports.PositionCatalogPort;
import com.emsafe.aiforecasting.domain.services.ExposureAnalyticsCalculator;
import com.emsafe.shared.exception.BadRequestException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Resumen analítico de una organización. La autorización la hace el controlador con
 * {@link ForecastingAccessPolicy} antes de llamar aquí.
 *
 * <ul>
 *   <li>{@code from} y {@code to} son días (inclusivos) en {@code zone}. Sin {@code to}, hasta hoy;
 *       sin {@code from}, los {@value #DEFAULT_RANGE_DAYS} días que terminan en {@code to}. Más de
 *       {@value #MAX_RANGE_DAYS} días, o
 *       {@code from} posterior a {@code to}, es 400.</li>
 *   <li>Zona por defecto {@value #DEFAULT_ZONE}; una zona inválida es 400.</li>
 *   <li>Los operarios críticos proyectados son una foto de ahora (no dependen del periodo): las
 *       evaluaciones vigentes de {@link OpenSessionAssessments} (jornadas no terminadas, una por
 *       operario), igual que {@code critical-workers}.</li>
 * </ul>
 */
public class AnalyticsSummaryQueryHandler {

    public static final String DEFAULT_ZONE = "America/Lima";
    static final int DEFAULT_RANGE_DAYS = 28;
    static final int MAX_RANGE_DAYS = 366;
    /** Tope de evaluaciones leídas para las recomendaciones del periodo (200 × 50). */
    static final int SEARCH_PAGE_SIZE = 200;
    static final int SEARCH_MAX_PAGES = 50;
    private final ClosedSessionHistoryPort history;
    private final PositionCatalogPort positions;
    private final AssessmentRepository assessments;
    private final OpenSessionAssessments openSessions;
    private final ExposureAnalyticsCalculator calculator;
    private final Clock clock;

    public AnalyticsSummaryQueryHandler(ClosedSessionHistoryPort history, PositionCatalogPort positions,
                                        AssessmentRepository assessments, OpenSessionAssessments openSessions,
                                        ExposureAnalyticsCalculator calculator, Clock clock) {
        this.history = history;
        this.positions = positions;
        this.assessments = assessments;
        this.openSessions = openSessions;
        this.calculator = calculator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnalyticsSummary summarize(Long organizationId, LocalDate from, LocalDate to, String zone) {
        if (organizationId == null) {
            throw new BadRequestException("organizationId is required");
        }
        ZoneId zoneId = zone(zone);
        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new BadRequestException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new BadRequestException("The period cannot exceed " + MAX_RANGE_DAYS + " days");
        }
        Instant fromInstant = start.atStartOfDay(zoneId).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(zoneId).toInstant();
        Instant now = clock.instant();

        List<ClosedExposureSession> closed = history.closedSessions(organizationId, fromInstant, toInstant);
        return calculator.summarize(organizationId, start, end, zoneId, now, closed,
                openSessions.current(organizationId),
                assessedIn(organizationId, fromInstant, toInstant),
                positions.positionNames(organizationId));
    }

    private static ZoneId zone(String zone) {
        if (zone == null || zone.isBlank()) {
            return ZoneId.of(DEFAULT_ZONE);
        }
        try {
            return ZoneId.of(zone.trim());
        } catch (DateTimeException e) {
            throw new BadRequestException("zone must be a valid IANA time zone id, e.g. " + DEFAULT_ZONE);
        }
    }

    /** {@code [from, to)}: la búsqueda del repositorio es inclusiva en {@code to}. */
    private List<PreventiveAssessment> assessedIn(Long organizationId, Instant from, Instant to) {
        AssessmentSearchCriteria criteria = new AssessmentSearchCriteria(organizationId, null, null, from,
                to.minusNanos(1_000));
        List<PreventiveAssessment> found = new ArrayList<>();
        for (int page = 0; page < SEARCH_MAX_PAGES; page++) {
            PageResult<PreventiveAssessment> result = assessments.search(criteria, page, SEARCH_PAGE_SIZE);
            found.addAll(result.content());
            if (result.content().size() < SEARCH_PAGE_SIZE || found.size() >= result.totalElements()) {
                break;
            }
        }
        return found;
    }
}
