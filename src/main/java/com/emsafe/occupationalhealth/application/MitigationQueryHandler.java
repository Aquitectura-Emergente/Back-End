package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.domain.model.MitigationAction;
import com.emsafe.occupationalhealth.domain.model.MitigationState;
import com.emsafe.occupationalhealth.domain.model.MitigationUnprocessableException;
import com.emsafe.occupationalhealth.domain.model.WorkerShift;
import com.emsafe.occupationalhealth.infrastructure.persistence.MitigationActionRepository;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Las consultas del contexto (informe §5.6.3:
 * GetPendingActionsQueryHandler, GetCurrentShiftQueryHandler y
 * GetComplianceReportQueryHandler).
 *
 * Van juntas en una clase y no en tres porque no tienen dependencias distintas
 * ni transacciones propias: son lecturas sobre los mismos dos repositorios. El
 * handler de reportes no está porque ComplianceReport llega en la siguiente
 * tanda.
 *
 * La autorización NO está aquí: la aplica el controller con
 * MitigationAccessPolicy antes de llamar. Lo que sí está es el filtro por
 * organización en cada consulta, que es lo que impide el cruce entre plantas.
 */
@Service
@RequiredArgsConstructor
public class MitigationQueryHandler {

    /** Tope del rango de un historial, como el ReportPeriod del informe. */
    private static final long MAX_PERIOD_DAYS = 366;

    private final MitigationActionRepository actionRepository;
    private final WorkerShiftRepository shiftRepository;

    @Transactional(readOnly = true)
    public List<MitigationAction> pendingActions(Long organizationId, String stateFilter) {
        return actionRepository.findByState(organizationId, MitigationState.fromFilter(stateFilter));
    }

    @Transactional(readOnly = true)
    public MitigationAction actionById(Long organizationId, Long actionId) {
        return actionRepository.findByIdAndOrganization(actionId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("MitigationAction", actionId));
    }

    @Transactional(readOnly = true)
    public List<MitigationAction> actionHistory(Long organizationId, Long workerId,
                                                LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        return actionRepository.findByWorkerAndPeriod(organizationId, workerId,
                from.atStartOfDay(), to.plusDays(1).atStartOfDay().minusNanos(1));
    }

    @Transactional(readOnly = true)
    public Optional<WorkerShift> currentShift(Long organizationId, Long workerId) {
        return shiftRepository.findActiveByWorker(organizationId, workerId);
    }

    @Transactional(readOnly = true)
    public List<WorkerShift> shiftHistory(Long organizationId, Long workerId,
                                          LocalDate from, LocalDate to) {
        validatePeriod(from, to);
        return shiftRepository.findByWorkerAndPeriod(organizationId, workerId, from, to);
    }

    /**
     * Mismas invariantes que el ReportPeriod del informe: el inicio no puede ser
     * posterior al fin y el rango no pasa de 366 días.
     */
    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new MitigationUnprocessableException("Both 'from' and 'to' dates are required");
        }
        if (from.isAfter(to)) {
            throw new MitigationUnprocessableException(
                    "Period start cannot be after its end: " + from + " → " + to);
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_PERIOD_DAYS) {
            throw new MitigationUnprocessableException(
                    "Period cannot span more than " + MAX_PERIOD_DAYS + " days");
        }
    }
}
