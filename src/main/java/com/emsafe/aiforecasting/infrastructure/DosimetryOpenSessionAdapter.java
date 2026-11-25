package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.domain.ports.OpenSessionPort;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Jornada abierta del operario a través de la fachada pública de Personal Dosimetry
 * ({@link ExposureQueryFacade#currentExposure}), sin cambiar su contrato.
 */
@Component
public class DosimetryOpenSessionAdapter implements OpenSessionPort {

    private final ExposureQueryFacade dosimetry;

    public DosimetryOpenSessionAdapter(ExposureQueryFacade dosimetry) {
        this.dosimetry = dosimetry;
    }

    @Override
    public Optional<String> openSessionId(Long organizationId, Long workerId) {
        return dosimetry.currentExposure(organizationId, workerId).map(ExposureQueryFacade.CurrentExposure::sessionId);
    }
}
