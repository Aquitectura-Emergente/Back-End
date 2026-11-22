package com.emsafe.personaldosimetry.application;

import com.emsafe.personaldosimetry.domain.model.ExposureIndex;
import com.emsafe.personaldosimetry.domain.model.ExposureSession;
import com.emsafe.personaldosimetry.infrastructure.persistence.ExposureSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Consulta pública de la exposición vigente de un operario, para los demás
 * contextos (informe §4.2.5, «consulta de la exposición a Personal Dosimetry»).
 *
 * <p>Se agregó el 2026-11-22 para que Occupational Health deje de calcular una
 * cifra provisional con el sensor de zona ({@code DosimetryExposureAdapter}, ya
 * eliminado) y use la misma que calcula este contexto. Es solo lectura: no abre,
 * no cierra ni modifica jornadas, y no pasa por {@link DosimetryActor} porque la
 * llama otro contexto del mismo proceso, no un usuario; la autorización la hace
 * quien la expone a un usuario.</p>
 *
 * <p><b>Contrato</b> (no cambiarlo sin avisar a Occupational Health):
 * {@code currentExposure(organizationId, workerId)} devuelve la jornada
 * {@code OPEN} más reciente del operario en esa organización, con el índice en
 * {@code uT*s} ({@link ExposureIndex#UNIT}) y el método
 * {@link ExposureIndex#METHOD}. Si no tiene jornada abierta devuelve vacío: la
 * exposición es desconocida, no cero.</p>
 */
@Service
@RequiredArgsConstructor
public class ExposureQueryFacade {

    private final ExposureSessionRepository sessions;

    /** La foto de la exposición vigente. {@code limitIndex} es el límite de la jornada. */
    public record CurrentExposure(
            String sessionId,
            BigDecimal accumulatedIndex,
            BigDecimal limitIndex,
            String unit,
            String methodId,
            Long jobPositionId,
            Instant latestCaptureTo) {
    }

    @Transactional(readOnly = true)
    public Optional<CurrentExposure> currentExposure(Long organizationId, Long workerId) {
        if (organizationId == null || workerId == null) {
            return Optional.empty();
        }
        return sessions.findFirstByOrganizationIdAndWorkerIdAndStateOrderByStartedAtDesc(
                        organizationId, workerId, ExposureSession.State.OPEN)
                .map(s -> new CurrentExposure(s.getId(), s.getAccumulatedIndex(), s.getLimitIndex(),
                        ExposureIndex.UNIT, ExposureIndex.METHOD, s.getJobPositionId(),
                        s.getLatestCaptureTo()));
    }
}
