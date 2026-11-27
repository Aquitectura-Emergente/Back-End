package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.application.commands.ExposureWarningRaisedCommand;
import com.emsafe.aiforecasting.application.commands.UpsertExposureSnapshotCommand;
import com.emsafe.aiforecasting.domain.model.AssessmentStatus;
import com.emsafe.aiforecasting.domain.model.CloseReason;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static com.emsafe.aiforecasting.application.ForecastingTestContext.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evaluaciones vigentes: solo de jornadas que no terminaron en Personal Dosimetry y una por operario.
 * Reproduce lo que dejaba la semilla: varias jornadas del mismo operario, cada una con su evaluación
 * activa para siempre.
 */
class OpenSessionAssessmentsTest {

    private final ForecastingTestContext ctx = new ForecastingTestContext();

    /** Jornada {@code sessionId} del operario con un aviso a las {@code hour}:00 (turno 08–16). */
    private String assessedSession(long worker, String sessionId, int hour) {
        ctx.clock.set(at(hour, 0));
        ctx.upsertSnapshot.handle(new UpsertExposureSnapshotCommand("snap-" + sessionId, null, ORG, worker, sessionId,
                ZONE, 10L, new BigDecimal("6000"), new BigDecimal(LIMIT), at(8, 0), at(16, 0), at(hour, 0)));
        return ctx.trigger.handle(new ExposureWarningRaisedCommand("warn-" + sessionId, null, ORG, sessionId, worker,
                new BigDecimal("11000"), new BigDecimal(LIMIT), 7L, at(hour, 0),
                ExposureWarningRaisedCommand.SUPPORTED_METHOD, ExposureWarningRaisedCommand.SUPPORTED_UNIT))
                .assessmentId();
    }

    @Test
    void severalActiveAssessmentsOfAWorkerCollapseToTheMostRecent() {
        assessedSession(101L, "S-old", 10);
        String recent = assessedSession(101L, "S-new", 12);

        assertThat(ctx.assessments.findActiveByOrganizationId(ORG)).hasSize(2); // lo que pasaba antes
        assertThat(ctx.openSessions.current(ORG)).extracting(PreventiveAssessment::getId).containsExactly(recent);
        assertThat(ctx.criticalWorkers.list(ORG, 0, 20).content()).extracting(PreventiveAssessment::getId)
                .containsExactly(recent);
        assertThat(ctx.assessmentQueries.activeForWorker(101L).getId()).isEqualTo(recent);
    }

    @Test
    void anAssessmentOfASessionClosedInPersonalDosimetryIsNoLongerCurrent() {
        String closed = assessedSession(101L, "S-closed", 12);
        String other = assessedSession(102L, "S-102", 12);
        ctx.dosimetry.close("S-closed", 101L, at(16, 0));
        ctx.clock.set(at(17, 0));

        assertThat(ctx.openSessions.current(ORG)).extracting(PreventiveAssessment::getId).containsExactly(other);
        assertThat(ctx.criticalWorkers.list(ORG, 0, 20).content()).extracting(PreventiveAssessment::getId)
                .doesNotContain(closed);
        assertThatThrownBy(() -> ctx.assessmentQueries.activeForWorker(101L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anotherOpenSessionOfTheWorkerMeansThisOneEnded() {
        assessedSession(101L, "S-yesterday", 10); // cerrando, PD aún no la tiene CLOSED
        ctx.dosimetry.open("S-today", 101L);

        assertThat(ctx.openSessions.current(ORG)).isEmpty();

        String today = assessedSession(101L, "S-today", 12);
        assertThat(ctx.openSessions.current(ORG)).extracting(PreventiveAssessment::getId).containsExactly(today);
    }

    @Test
    void sessionsUnknownToPersonalDosimetryAreKept() {
        String inboxOnly = assessedSession(101L, "S-inbox", 12);

        assertThat(ctx.openSessions.current(ORG)).extracting(PreventiveAssessment::getId).containsExactly(inboxOnly);
    }

    @Test
    void closeEndedClosesOnlyEndedSessionsWithSessionEndedAndIsIdempotent() {
        String closed = assessedSession(101L, "S-closed", 10);
        String superseded = assessedSession(102L, "S-102-old", 10);
        String open = assessedSession(102L, "S-102-new", 12);
        String inboxOnly = assessedSession(103L, "S-103", 12);
        ctx.dosimetry.close("S-closed", 101L, at(16, 0));
        ctx.dosimetry.open("S-102-new", 102L);
        Instant now = at(17, 0);
        ctx.clock.set(now);

        assertThat(ctx.closeEnded.organizationsWithActiveAssessments()).containsExactly(ORG);
        assertThat(ctx.closeEnded.closeEnded(ORG)).isEqualTo(2);
        assertThat(ctx.closeEnded.closeEnded(ORG)).isZero();

        for (String id : new String[]{closed, superseded}) {
            PreventiveAssessment a = ctx.assessments.findById(id).orElseThrow();
            assertThat(a.getStatus()).isEqualTo(AssessmentStatus.CLOSED);
            assertThat(a.getCloseReason()).isEqualTo(CloseReason.SESSION_ENDED);
            assertThat(a.getClosedAt()).isEqualTo(now);
        }
        assertThat(ctx.assessments.findById(open).orElseThrow().isActive()).isTrue();
        assertThat(ctx.assessments.findById(inboxOnly).orElseThrow().isActive()).isTrue();
    }
}
