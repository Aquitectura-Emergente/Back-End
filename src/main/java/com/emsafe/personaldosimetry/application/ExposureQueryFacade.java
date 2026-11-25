package com.emsafe.personaldosimetry.application;

import com.emsafe.personaldosimetry.domain.model.ExposureIndex;
import com.emsafe.personaldosimetry.domain.model.ExposureSession;
import com.emsafe.personaldosimetry.infrastructure.persistence.ExposureSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    /**
     * Una jornada ya consolidada ({@code CLOSED}): sus cifras están congeladas.
     * {@code limitIndex}, {@code warningRatio} y {@code jobPositionId} son los
     * vigentes al cerrar (un {@code ShiftUpdated} pudo cambiarlos durante la
     * jornada). {@code warningRaised} y {@code limitExceeded} son las banderas que
     * calculó este contexto, no una reinterpretación del consumidor.
     */
    public record ClosedSession(
            String sessionId,
            Long workerId,
            Long jobPositionId,
            BigDecimal accumulatedIndex,
            BigDecimal limitIndex,
            BigDecimal warningRatio,
            boolean warningRaised,
            boolean limitExceeded,
            Instant startedAt,
            Instant endedAt,
            Instant finalizedAt,
            BigDecimal coveragePercentage,
            boolean coverageComplete,
            String unit,
            String methodId) {
    }

    /**
     * Histórico de jornadas consolidadas de una organización (agregado
     * 2026-11-25 para la analítica de AI Forecasting).
     *
     * <p><b>Contrato:</b> solo jornadas {@code CLOSED} (las {@code CLOSING} aún
     * pueden recibir capturas y cambiar) cuyo {@code endedAt} cae en
     * {@code [from, to)}, ordenadas por {@code endedAt} ascendente. Índices en
     * {@code uT*s}. Con argumentos nulos o {@code from} no anterior a {@code to}
     * devuelve una lista vacía. Como {@link #currentExposure}, no autoriza: lo hace
     * quien la expone a un usuario, y acotar el rango es responsabilidad suya.</p>
     */
    @Transactional(readOnly = true)
    public List<ClosedSession> closedSessions(Long organizationId, Instant from, Instant to) {
        if (organizationId == null || from == null || to == null || !from.isBefore(to)) {
            return List.of();
        }
        return sessions.findEndedBetween(organizationId, ExposureSession.State.CLOSED, from, to).stream()
                .map(s -> new ClosedSession(s.getId(), s.getWorkerId(), s.getJobPositionId(),
                        s.getAccumulatedIndex(), s.getLimitIndex(), s.getWarningRatio(),
                        s.isWarningRaised(), s.isLimitExceeded(), s.getStartedAt(), s.getEndedAt(),
                        s.getFinalizedAt(), s.coverage(s.getEndedAt()), s.coverageComplete(),
                        ExposureIndex.UNIT, ExposureIndex.METHOD))
                .toList();
    }
}
