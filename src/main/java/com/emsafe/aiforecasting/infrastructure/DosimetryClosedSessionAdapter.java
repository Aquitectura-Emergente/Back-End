package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.domain.model.ClosedExposureSession;
import com.emsafe.aiforecasting.domain.model.ExposureMethod;
import com.emsafe.aiforecasting.domain.ports.ClosedSessionHistoryPort;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade.ClosedSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Histórico de jornadas cerradas a través de la fachada pública de Personal Dosimetry
 * ({@link ExposureQueryFacade#closedSessions}), nunca de su tabla. Descarta, con aviso en el log,
 * cualquier jornada con otro método o unidad: el contrato congelado es {@code uT*s}.
 */
@Component
public class DosimetryClosedSessionAdapter implements ClosedSessionHistoryPort {

    private static final Logger log = LoggerFactory.getLogger(DosimetryClosedSessionAdapter.class);

    private final ExposureQueryFacade dosimetry;

    public DosimetryClosedSessionAdapter(ExposureQueryFacade dosimetry) {
        this.dosimetry = dosimetry;
    }

    @Override
    public List<ClosedExposureSession> closedSessions(Long organizationId, Instant from, Instant to) {
        return dosimetry.closedSessions(organizationId, from, to).stream()
                .filter(DosimetryClosedSessionAdapter::supported)
                .map(DosimetryClosedSessionAdapter::toDomain)
                .toList();
    }

    private static boolean supported(ClosedSession s) {
        boolean ok = ExposureMethod.MAGNETIC_TIME_INDEX_V1.equals(s.methodId())
                && ExposureMethod.MICROTESLA_SECONDS.equals(s.unit())
                && s.limitIndex() != null && s.limitIndex().signum() > 0
                && s.accumulatedIndex() != null && s.endedAt() != null;
        if (!ok) {
            log.warn("Skipping closed session {} with unsupported method/unit or missing figures ({} {})",
                    s.sessionId(), s.methodId(), s.unit());
        }
        return ok;
    }

    private static ClosedExposureSession toDomain(ClosedSession s) {
        return new ClosedExposureSession(s.sessionId(), s.workerId(), s.jobPositionId(), s.accumulatedIndex(),
                s.limitIndex(), s.warningRaised(), s.limitExceeded(), s.startedAt(), s.endedAt(),
                s.coverageComplete());
    }
}
