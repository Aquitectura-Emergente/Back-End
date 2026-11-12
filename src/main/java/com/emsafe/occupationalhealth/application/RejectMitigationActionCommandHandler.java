package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.application.events.EventEnvelope;
import com.emsafe.occupationalhealth.application.events.MitigationActionRejected;
import com.emsafe.occupationalhealth.domain.model.Decision;
import com.emsafe.occupationalhealth.domain.model.MitigationAction;
import com.emsafe.occupationalhealth.domain.model.MitigationAuditEntry;
import com.emsafe.occupationalhealth.infrastructure.MitigationAuditAdapter;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rechaza una medida y deja constancia del motivo (informe §5.6.3).
 *
 * No toca turnos: rechazar es decidir que nada cambie en la planta.
 */
@Service
@RequiredArgsConstructor
public class RejectMitigationActionCommandHandler {

    private final MitigationActionRepository actionRepository;
    private final MitigationAuditAdapter audit;
    private final ApplicationEventPublisher events;

    @Transactional
    public MitigationAction handle(Long organizationId, Long actionId, Long ssoUserId,
                                   String reason) {
        MitigationAction action = actionRepository
                .findByIdAndOrganization(actionId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("MitigationAction", actionId));

        // El motivo obligatorio lo exige Decision.rejectedBy, no este handler:
        // así ningún otro flujo puede rechazar sin justificar.
        action.reject(Decision.rejectedBy(ssoUserId, reason));
        MitigationAction saved = actionRepository.save(action);

        audit.recordGranted(organizationId, ssoUserId, "MitigationAction", saved.getId(),
                MitigationAuditEntry.ACTION_REJECT, "Rejected: " + reason);

        events.publishEvent(new MitigationActionRejected(
                EventEnvelope.of(organizationId), saved.getId(), saved.affectedWorkerIds(),
                saved.getOutcome(), saved.getDecision().getDecidedAt(), ssoUserId));
        return saved;
    }
}
