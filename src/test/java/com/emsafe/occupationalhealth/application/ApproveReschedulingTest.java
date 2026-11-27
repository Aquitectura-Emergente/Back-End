package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.domain.model.DoseSnapshot;
import com.emsafe.occupationalhealth.domain.model.MitigationAction;
import com.emsafe.occupationalhealth.domain.model.MitigationOrigin;
import com.emsafe.occupationalhealth.domain.model.MitigationState;
import com.emsafe.occupationalhealth.domain.model.MitigationTrigger;
import com.emsafe.occupationalhealth.domain.model.MitigationType;
import com.emsafe.occupationalhealth.domain.services.ExposureQueryPort;
import com.emsafe.occupationalhealth.domain.services.RotationFeasibilityService;
import com.emsafe.occupationalhealth.domain.services.ZoneExposurePort;
import com.emsafe.occupationalhealth.infrastructure.MitigationAuditAdapter;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Aprobar una reprogramación no mueve al operario ni necesita un turno abierto. */
class ApproveReschedulingTest {

    private final MitigationActionRepository actions = mock(MitigationActionRepository.class);
    private final WorkerShiftRepository shifts = mock(WorkerShiftRepository.class);
    private final ExposureQueryPort exposure = mock(ExposureQueryPort.class);
    private final ApproveMitigationActionCommandHandler handler = new ApproveMitigationActionCommandHandler(
            actions, shifts, exposure, mock(ZoneExposurePort.class), new RotationFeasibilityService(),
            mock(MitigationAuditAdapter.class), mock(ApplicationEventPublisher.class));

    @Test
    void aReschedulingIsAppliedWithoutReassigningTheShift() {
        MitigationAction rescheduling = MitigationAction.propose(8L, MitigationType.RESCHEDULING,
                MitigationOrigin.MANUAL, MitigationTrigger.manual(), "Sin candidato viable");
        DoseSnapshot dose = DoseSnapshot.of(new BigDecimal("6540"), "uT*s", "M", new BigDecimal("3000"));
        rescheduling.addAffectedWorkerPendingTarget(18L, null, dose);
        rescheduling.setId(9L);
        when(actions.findByIdAndOrganization(9L, 8L)).thenReturn(Optional.of(rescheduling));
        when(actions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(exposure.currentExposure(anyLong(), anyLong())).thenReturn(dose);

        MitigationAction saved = handler.handle(8L, 9L, 11L, "Se reprograma la tarea", null, null);

        assertThat(saved.getState()).isIn(MitigationState.APPLIED, MitigationState.APPROVED);
        verify(shifts, never()).findActiveByWorker(anyLong(), anyLong());
    }
}
