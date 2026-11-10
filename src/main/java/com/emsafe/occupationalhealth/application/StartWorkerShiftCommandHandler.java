package com.emsafe.occupationalhealth.application;

import com.emsafe.occupationalhealth.domain.model.MitigationConflictException;
import com.emsafe.occupationalhealth.domain.model.WorkerShift;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Abre el turno de un operario en un puesto y una zona.
 *
 * ── Añadido que el informe no cubre ───────────────────────────────────────────
 * §5.6.2 define WorkerShiftController con getCurrent() y getHistory(), y §5.6.3
 * cierra turnos al recibir ExposureSessionClosed, pero **nada en el informe
 * abre un turno**. Sin esa capacidad el contexto no arranca: una reubicación o
 * una rotación reasignan tramos dentro de un turno vigente, así que sin turno no
 * hay nada que mitigar.
 *
 * Se añade como comando propio en lugar de crear el turno al vuelo dentro de la
 * reubicación, porque abrir un turno es un hecho del negocio (el operario entró
 * a planta) y no un efecto colateral de una medida.
 */
@Service
@RequiredArgsConstructor
public class StartWorkerShiftCommandHandler {

    private final WorkerShiftRepository shiftRepository;

    @Transactional
    public WorkerShift handle(Long organizationId, Long workerId, Long jobPositionId,
                              Long plantZoneId, String exposureSessionId) {
        shiftRepository.findActiveByWorker(organizationId, workerId).ifPresent(open -> {
            throw new MitigationConflictException(
                    "Worker " + workerId + " already has an active shift (#" + open.getId() + ")");
        });

        WorkerShift shift = WorkerShift.start(organizationId, workerId, jobPositionId,
                plantZoneId, LocalDateTime.now());
        if (exposureSessionId != null && !exposureSessionId.isBlank()) {
            shift.linkExposureSession(exposureSessionId);
        }
        return shiftRepository.save(shift);
    }

    /** Cierra el turno vigente del operario. Idempotente si ya no hay ninguno. */
    @Transactional
    public void close(Long organizationId, Long workerId) {
        shiftRepository.findActiveByWorker(organizationId, workerId).ifPresent(shift -> {
            shift.close(LocalDateTime.now());
            shiftRepository.save(shift);
        });
    }
}
