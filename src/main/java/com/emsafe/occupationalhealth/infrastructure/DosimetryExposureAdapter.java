package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.occupationalhealth.domain.model.DoseSnapshot;
import com.emsafe.occupationalhealth.domain.model.PositionAssignment;
import com.emsafe.occupationalhealth.domain.model.WorkerShift;
import com.emsafe.occupationalhealth.domain.services.ExposureQueryPort;
import com.emsafe.occupationalhealth.infrastructure.persistence.WorkerShiftRepository;
import com.emsafe.radiationmonitoring.domain.model.RadiationReading;
import com.emsafe.radiationmonitoring.infrastructure.persistence.RadiationReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Implementa ExposureQueryPort (informe §5.6.4, DosimetryExposureAdapter).
 *
 * ⚠ ESTE ADAPTADOR ES PROVISIONAL Y NO ES DOSIMETRÍA.
 *
 * El informe hace que este puerto consulte a Personal Dosimetry & Exposure, que
 * es la autoridad del cálculo y del límite del puesto. Ese contexto todavía no
 * tiene código, así que mientras no exista esta clase deriva una cifra
 * aproximada de las lecturas del sensor de la zona donde estuvo el operario:
 *
 *   dosis ≈ Σ (media de µT de la zona en el tramo × horas del tramo)
 *
 * Qué NO es:
 *  - No es una magnitud dosimétrica normalizada. Es una exposición media
 *    ponderada por tiempo, en µT·h, calculada con el sensor de zona y no con un
 *    dosímetro personal.
 *  - El límite NO viene de ninguna norma. Por defecto son 800 µT·h, que es
 *    simplemente el umbral de precaución del propio proyecto (100 µT, ver
 *    RadiationLevel.CAUTION_UT) sostenido durante una jornada de 8 horas. Se
 *    deja configurable justamente porque es un valor de relleno.
 *  - methodId viaja como PROVISIONAL-TWA-V1 para que cualquier acción o reporte
 *    deje constancia de que la cifra no la produjo Dosimetry.
 *
 * Cuando exista Personal Dosimetry, esta clase se sustituye por una llamada a su
 * interfaz pública y nada del dominio cambia: para eso está el puerto.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DosimetryExposureAdapter implements ExposureQueryPort {

    /** Marca de que la cifra es provisional y no la calculó Personal Dosimetry. */
    public static final String PROVISIONAL_METHOD_ID = "PROVISIONAL-TWA-V1";
    private static final String UNIT = "uT.h";

    private final WorkerShiftRepository shiftRepository;
    private final RadiationReadingRepository readingRepository;

    /** Límite de relleno, en µT·h. Ver la advertencia de la clase. */
    @Value("${emsafe.occupational-health.provisional-dose-limit-ut-hours:800}")
    private BigDecimal provisionalLimit;

    @Override
    public DoseSnapshot currentExposure(Long organizationId, Long workerId) {
        return shiftRepository.findActiveByWorker(organizationId, workerId)
                .map(this::accumulate)
                .orElseGet(() -> {
                    log.debug("Worker {} has no active shift; exposure unknown", workerId);
                    return unknown();
                });
    }

    /**
     * Suma la contribución de cada tramo del turno. Un tramo sin lecturas no
     * aporta: es preferible quedarse corto y que el SSO lo vea en la foto, a
     * rellenar con un valor inventado.
     */
    private DoseSnapshot accumulate(WorkerShift shift) {
        BigDecimal total = BigDecimal.ZERO;
        for (PositionAssignment assignment : shift.getAssignments()) {
            total = total.add(contributionOf(assignment));
        }
        return DoseSnapshot.of(total.setScale(4, RoundingMode.HALF_UP),
                UNIT, PROVISIONAL_METHOD_ID, provisionalLimit);
    }

    private BigDecimal contributionOf(PositionAssignment assignment) {
        LocalDateTime from = assignment.getInterval().getFrom();
        LocalDateTime to = assignment.getInterval().getTo() != null
                ? assignment.getInterval().getTo()
                : LocalDateTime.now();

        List<RadiationReading> inWindow = readingRepository
                .findByDeviceIdWithDevice(assignment.getPlantZoneId())
                .stream()
                // La consulta devuelve de más reciente a más antigua, así que en
                // cuanto se cruza el inicio del tramo ya no hay nada útil detrás.
                .takeWhile(r -> !r.timestamp().isBefore(from))
                .filter(r -> r.timestamp().isBefore(to))
                .filter(r -> r.getValue() != null)
                .toList();

        if (inWindow.isEmpty()) return BigDecimal.ZERO;

        double meanMicrotesla = inWindow.stream()
                .mapToDouble(RadiationReading::getValue)
                .average()
                .orElse(0d);
        BigDecimal hours = BigDecimal.valueOf(assignment.getInterval().durationMinutes())
                .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);

        return BigDecimal.valueOf(meanMicrotesla).multiply(hours);
    }

    /** Foto vacía: el puerto promete no devolver null. */
    private DoseSnapshot unknown() {
        return DoseSnapshot.of(null, UNIT, PROVISIONAL_METHOD_ID, provisionalLimit);
    }
}
