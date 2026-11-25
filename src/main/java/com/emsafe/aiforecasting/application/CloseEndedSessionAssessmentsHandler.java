package com.emsafe.aiforecasting.application;

import com.emsafe.aiforecasting.domain.model.CloseReason;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Cierra con {@link CloseReason#SESSION_ENDED} las evaluaciones activas cuya jornada ya terminó en
 * Personal Dosimetry (mismo criterio que {@link OpenSessionAssessments}). Lo ejecuta una tarea
 * programada y la semilla de demo. Una transacción por organización: un conflicto en una no deshace
 * las demás.
 */
public class CloseEndedSessionAssessmentsHandler {

    private final AssessmentRepository assessments;
    private final OpenSessionAssessments openSessions;
    private final Clock clock;

    public CloseEndedSessionAssessmentsHandler(AssessmentRepository assessments, OpenSessionAssessments openSessions,
                                               Clock clock) {
        this.assessments = assessments;
        this.openSessions = openSessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Long> organizationsWithActiveAssessments() {
        return assessments.findOrganizationsWithActiveAssessments();
    }

    /** @return evaluaciones cerradas */
    @Transactional
    public int closeEnded(Long organizationId) {
        List<PreventiveAssessment> active = assessments.findActiveByOrganizationId(organizationId);
        Set<String> ended = openSessions.endedSessionIds(organizationId, active);
        Instant now = clock.instant();
        int closed = 0;
        for (PreventiveAssessment a : active) {
            if (ended.contains(a.getSessionId())) {
                a.close(CloseReason.SESSION_ENDED, now.isBefore(a.getAssessedAt()) ? a.getAssessedAt() : now);
                assessments.save(a);
                closed++;
            }
        }
        return closed;
    }
}
