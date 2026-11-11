package com.emsafe.aiforecasting.domain.services;

import com.emsafe.aiforecasting.domain.model.Decimals;
import com.emsafe.aiforecasting.domain.model.ExposureProjection;
import com.emsafe.aiforecasting.domain.model.ForecastingRuleException;
import com.emsafe.aiforecasting.domain.model.WorkerExposureSnapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static com.emsafe.aiforecasting.domain.model.ForecastingRuleException.requirePresent;

/**
 * Algoritmo {@code LINEAR_RATE_V1}: determinista y reproducible.
 *
 * <pre>
 * elapsed   = capturedAt − sessionStartedAt   (s, debe ser &gt; 0)
 * rate      = currentIndex / elapsed
 * remaining = max(0, shiftEnd − capturedAt)
 * projected = currentIndex + rate × remaining
 * excedencia estimada si projected &gt; limit y rate &gt; 0 (o capturedAt si current ≥ limit)
 * </pre>
 */
public class ExposureProjectionService {

    public ExposureProjection project(WorkerExposureSnapshot snapshot, Duration defaultShift) {
        requirePresent(snapshot, "snapshot");
        return project(snapshot.accumulatedIndex(), snapshot.limitIndex(), snapshot.sessionStartedAt(),
                snapshot.capturedAt(), snapshot.shiftEnd(defaultShift));
    }

    public ExposureProjection project(BigDecimal currentIndex, BigDecimal limitIndex,
                                      Instant sessionStartedAt, Instant capturedAt, Instant shiftEnd) {
        requirePresent(limitIndex, "limitIndex");
        requirePresent(shiftEnd, "shiftEnd");
        if (limitIndex.signum() <= 0) {
            throw ForecastingRuleException.invalid("limitIndex must be greater than zero");
        }
        BigDecimal current = Decimals.scaled(requirePresent(currentIndex, "currentIndex"));
        BigDecimal limit = Decimals.scaled(limitIndex);
        BigDecimal rate = accumulationRate(current, sessionStartedAt, capturedAt);
        BigDecimal remaining = remainingSeconds(capturedAt, shiftEnd);

        BigDecimal projected = Decimals.scaled(current.add(rate.multiply(remaining)));
        BigDecimal margin = Decimals.scaled(limit.subtract(current));

        return new ExposureProjection(current, limit, rate, projected, margin,
                Decimals.divide(current, limit), Decimals.divide(projected, limit),
                estimatedExceedance(current, limit, rate, projected, capturedAt), shiftEnd);
    }

    /** Tasa de acumulación (uT·s por segundo). Lanza 422 si el tiempo transcurrido no es positivo. */
    public BigDecimal accumulationRate(BigDecimal currentIndex, Instant sessionStartedAt, Instant capturedAt) {
        requirePresent(currentIndex, "currentIndex");
        requirePresent(sessionStartedAt, "sessionStartedAt");
        requirePresent(capturedAt, "capturedAt");
        if (currentIndex.signum() < 0) {
            throw ForecastingRuleException.invalid("currentIndex must not be negative");
        }
        BigDecimal elapsed = Decimals.seconds(Duration.between(sessionStartedAt, capturedAt));
        if (elapsed.signum() <= 0) {
            throw ForecastingRuleException.invalid("capturedAt must be after sessionStartedAt");
        }
        return Decimals.divide(currentIndex, elapsed);
    }

    /** Segundos de turno restantes desde la captura, nunca negativos. */
    public static BigDecimal remainingSeconds(Instant capturedAt, Instant shiftEnd) {
        requirePresent(capturedAt, "capturedAt");
        requirePresent(shiftEnd, "shiftEnd");
        BigDecimal remaining = Decimals.seconds(Duration.between(capturedAt, shiftEnd));
        return remaining.signum() > 0 ? remaining : Decimals.scaled(BigDecimal.ZERO);
    }

    private static Instant estimatedExceedance(BigDecimal current, BigDecimal limit, BigDecimal rate,
                                               BigDecimal projected, Instant capturedAt) {
        if (current.compareTo(limit) >= 0) {
            return capturedAt;
        }
        if (projected.compareTo(limit) > 0 && rate.signum() > 0) {
            BigDecimal secondsToLimit = Decimals.divide(limit.subtract(current), rate);
            return capturedAt.plus(Decimals.toDuration(secondsToLimit));
        }
        return null;
    }
}
