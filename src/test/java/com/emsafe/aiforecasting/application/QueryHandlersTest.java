package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import com.emsafe.aiforecasting.domain.model.CriticalityLevel;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.AssessmentSearchCriteria;
import com.emsafe.aiforecasting.domain.ports.PageResult;
import com.emsafe.shared.exception.BadRequestException;
import com.emsafe.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static com.emsafe.aiforecasting.application.ForecastingTestContext.*;
import static org.assertj.core.api.Assertions.*;

class QueryHandlersTest {

    private final ForecastingTestContext ctx = new ForecastingTestContext();

    /** 101 HIGH (rotación con 200), 300 CRITICAL (zona propia), 200 LOW. */
    private void seedThreeWorkers() {
        ctx.seedHighScenario();
        ctx.upsertSnapshot.handle(new UpsertExposureSnapshotCommand("snap-300", null, ORG, 300L, session(300L),
                "Z-9", 30L, new BigDecimal("10000"), new BigDecimal(LIMIT), at(8, 0), at(16, 0), at(12, 0)));
        ctx.trigger.handle(warningCmd("w-101", 101L, "7200"));
        ctx.trigger.handle(warningCmd("w-300", 300L, "11000"));
        ctx.trigger.handle(warningCmd("w-200", 200L, "1440"));
    }

    // ─── AssessmentQueryHandler ──────────────────────────────────────────────

    @Test
    void getsAssessmentById() {
        seedThreeWorkers();
        PreventiveAssessment any = ctx.assessments.all().get(0);

        assertThat(ctx.assessmentQueries.getById(any.getId())).isSameAs(any);
    }

    @Test
    void unknownIdIsNotFound() {
        assertThatThrownBy(() -> ctx.assessmentQueries.getById("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void activeAssessmentOfWorker() {
        seedThreeWorkers();

        assertThat(ctx.assessmentQueries.activeForWorker(300L).getCriticality()).isEqualTo(CriticalityLevel.CRITICAL);
        assertThatThrownBy(() -> ctx.assessmentQueries.activeForWorker(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void searchFiltersAndPaginates() {
        seedThreeWorkers();
        ctx.clock.advance(Duration.ofMinutes(5));
        ctx.trigger.handle(warningCmd("w-101b", 101L, "7300"));

        PageResult<PreventiveAssessment> byWorker = ctx.assessmentQueries.search(
                new AssessmentSearchCriteria(ORG, 101L, null, null, null), 0, 10);
        assertThat(byWorker.totalElements()).isEqualTo(2);
        assertThat(byWorker.content()).extracting(PreventiveAssessment::getTriggerEventId)
                .containsExactly("w-101b", "w-101");

        PageResult<PreventiveAssessment> page = ctx.assessmentQueries.search(
                new AssessmentSearchCriteria(ORG, null, null, null, null), 1, 3);
        assertThat(page.totalElements()).isEqualTo(4);
        assertThat(page.content()).hasSize(1);

        PageResult<PreventiveAssessment> window = ctx.assessmentQueries.search(
                new AssessmentSearchCriteria(ORG, null, null, at(12, 1), at(13, 0)), 0, 10);
        assertThat(window.content()).extracting(PreventiveAssessment::getTriggerEventId).containsExactly("w-101b");
    }

    @Test
    void invalidPagingIsBadRequest() {
        AssessmentSearchCriteria all = new AssessmentSearchCriteria(null, null, null, null, null);

        assertThatThrownBy(() -> ctx.assessmentQueries.search(all, -1, 10)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ctx.assessmentQueries.search(all, 0, 0)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ctx.assessmentQueries.search(all, 0, 201)).isInstanceOf(BadRequestException.class);
        assertThatCode(() -> ctx.assessmentQueries.search(all, 0, 200)).doesNotThrowAnyException();
    }

    // ─── CriticalWorkersQueryHandler ─────────────────────────────────────────

    @Test
    void criticalWorkersSortedByCriticalityThenExceedance() {
        seedThreeWorkers();

        PageResult<PreventiveAssessment> result = ctx.criticalWorkers.list(ORG, 0, 20);

        assertThat(result.content()).extracting(PreventiveAssessment::getWorkerId).containsExactly(300L, 101L, 200L);
        assertThat(result.totalElements()).isEqualTo(3);
    }

    @Test
    void criticalWorkersOnlyActiveAndOnlyOwnOrganization() {
        seedThreeWorkers();
        ctx.clock.advance(Duration.ofMinutes(5));
        ctx.trigger.handle(warningCmd("w-101b", 101L, "7300"));

        assertThat(ctx.criticalWorkers.list(ORG, 0, 20).content()).hasSize(3).allMatch(PreventiveAssessment::isActive);
        assertThat(ctx.criticalWorkers.list(2L, 0, 20).content()).isEmpty();
    }

    @Test
    void criticalWorkersPaginatesAndValidates() {
        seedThreeWorkers();

        assertThat(ctx.criticalWorkers.list(ORG, 1, 2).content())
                .extracting(PreventiveAssessment::getWorkerId).containsExactly(200L);
        assertThatThrownBy(() -> ctx.criticalWorkers.list(null, 0, 20)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ctx.criticalWorkers.list(ORG, 0, 500)).isInstanceOf(BadRequestException.class);
    }
}
