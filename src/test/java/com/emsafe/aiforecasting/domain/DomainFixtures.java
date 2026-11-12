package com.emsafe.aiforecasting.domain;

import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ForecastingThresholds;
import com.emsafe.aiforecasting.domain.model.ModelVersion;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;

import java.math.BigDecimal;
import java.time.Instant;

/** Datos de prueba compartidos por los tests de dominio. Todas las horas son UTC. */
public final class DomainFixtures {

    public static final Long ORG = 1L;
    public static final String ZONE = "Z-1";
    public static final Instant DAY = Instant.parse("2026-11-10T00:00:00Z");
    public static final ModelVersion MODEL = new ModelVersion("LINEAR_RATE", "1");
    public static final ForecastingThresholds DEFAULT_THRESHOLDS = new ForecastingThresholds(
            new BigDecimal("0.10"), new BigDecimal("0.90"), new BigDecimal("0.90"));
    public static final String ASSESSMENT_ID = "6f1c0d3e-2b7a-4c55-9a1e-3f2d4b5c6a7e";

    private DomainFixtures() {
    }

    public static Instant at(int hour, int minute) {
        return DAY.plusSeconds(hour * 3600L + minute * 60L);
    }

    public static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    /** Snapshot con turno 08:00–16:00 de forma explícita. */
    public static WorkerExposureSnapshot snapshot(long workerId, Long positionId, String current,
                                                  String limit, Instant startedAt, Instant capturedAt) {
        return new WorkerExposureSnapshot(ORG, workerId, "S-" + workerId, ZONE, positionId,
                dec(current), dec(limit), startedAt, at(16, 0), capturedAt, capturedAt);
    }

    /** Proyección construida a mano, para los tests que no prueban el cálculo. */
    public static ExposureProjection projection(String current, String limit, String projected) {
        BigDecimal c = dec(current);
        BigDecimal l = dec(limit);
        BigDecimal p = dec(projected);
        return new ExposureProjection(c, l, dec("0.5"), p, l.subtract(c),
                c.divide(l, 6, java.math.RoundingMode.HALF_UP),
                p.divide(l, 6, java.math.RoundingMode.HALF_UP),
                p.compareTo(l) > 0 ? at(14, 40) : null, at(16, 0));
    }

    public static PreventiveAssessment projectedAssessment(ExposureProjection projection) {
        return PreventiveAssessment.project(ASSESSMENT_ID, ORG, 101L, "S-101", projection, MODEL,
                7L, "evt-1", at(10, 0));
    }
}
