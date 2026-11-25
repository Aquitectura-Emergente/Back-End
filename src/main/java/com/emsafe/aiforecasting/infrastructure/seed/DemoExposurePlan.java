package com.emsafe.aiforecasting.infrastructure.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Guion determinista de la demo de analítica, sin dependencias: quién trabaja, en qué puesto,
 * cuánto acumula cada jornada y qué queda abierto hoy. El seeder solo lo ejecuta contra los
 * servicios de los demás contextos.
 *
 * <ul>
 *   <li>4 operarios WORKER de Quantum; el primero es el que ya siembra DataInitializer.</li>
 *   <li>Historial: los días hábiles (lunes a viernes) de las {@value #HISTORY_DAYS} jornadas
 *       anteriores a hoy en Lima, es decir, siempre 15 días × 4 operarios = 60 jornadas, de 08:00 a
 *       16:00 de Lima. Cada jornada es una sola captura que la cubre entera, salvo una con 30 minutos
 *       sin cubrir (cobertura incompleta).</li>
 *   <li>Límite {@code 12000 uT*s} con advertencia en 0.8. Hay 9 jornadas sobre la advertencia y
 *       <b>una sola</b> que supera el límite (operario 1), para que Occupational Health reciba una
 *       única excedencia.</li>
 *   <li>Hoy: una jornada abierta por operario, iniciada hace 4 horas. Los dos primeros quedan sobre
 *       la advertencia, así que AI Forecasting los evalúa (HIGH y CRITICAL) cuando el relay entrega
 *       el aviso.</li>
 * </ul>
 */
final class DemoExposurePlan {

    static final String COMPANY_EMAIL = "ops@quantumdyn.com";
    static final String ADMIN_EMAIL = "admin@emsafe.com";
    static final String WORKER_PASSWORD = "worker123";
    static final ZoneId ZONE = ZoneId.of("America/Lima");
    static final LocalTime SHIFT_START = LocalTime.of(8, 0);
    static final Duration SHIFT = Duration.ofHours(8);
    static final int HISTORY_DAYS = 21;
    static final Duration OPEN_ELAPSED = Duration.ofHours(4);
    static final BigDecimal LIMIT_INDEX = new BigDecimal("12000");
    static final BigDecimal WARNING_RATIO = new BigDecimal("0.8");
    static final String LIMIT_REFERENCE = "AF-DEMO-INDEX";
    static final String PLANT_ZONE = "Planta AF demo";
    static final List<String> POSITIONS = List.of("Prensa hidráulica", "Soldadura", "Inspección de calidad");

    record Worker(String email, String name, String initials, int position, String dosimeterSerial) {
    }

    static final List<Worker> WORKERS = List.of(
            new Worker("operario1@quantumdyn.com", "Carlos Rojas", "CR", 0, "AF-DEMO-DOS-01"),
            new Worker("operario3@quantumdyn.com", "Lucía Mendoza", "LM", 1, "AF-DEMO-DOS-02"),
            new Worker("operario4@quantumdyn.com", "Jorge Salas", "JS", 2, "AF-DEMO-DOS-03"),
            new Worker("operario5@quantumdyn.com", "María Huamán", "MH", 1, "AF-DEMO-DOS-04"));

    /** Índice final / límite de cada jornada hábil, de la más antigua a la más reciente (15 por operario). */
    private static final double[][] RATIOS = {
            {0.72, 0.75, 0.70, 0.78, 0.74, 0.79, 0.83, 0.77, 0.85, 0.80, 0.86, 0.82, 1.03, 0.88, 0.84},
            {0.62, 0.66, 0.60, 0.68, 0.64, 0.70, 0.72, 0.67, 0.74, 0.69, 0.81, 0.73, 0.76, 0.91, 0.78},
            {0.22, 0.25, 0.21, 0.27, 0.24, 0.26, 0.23, 0.28, 0.25, 0.30, 0.27, 0.24, 0.29, 0.31, 0.26},
            {0.52, 0.55, 0.50, 0.58, 0.54, 0.56, 0.53, 0.60, 0.57, 0.55, 0.59, 0.54, 0.61, 0.58, 0.56}};

    /** La única jornada con cobertura incompleta: (operario, día hábil). */
    static final int INCOMPLETE_WORKER = 2;
    static final int INCOMPLETE_DAY = 4;
    static final Duration UNCOVERED = Duration.ofMinutes(30);

    /** Índice acumulado a las 4 h de la jornada abierta de hoy (advertencia en 9600). */
    private static final String[] OPEN_INDEX = {"9800", "10900", "2600", "6000"};

    /**
     * Una jornada del guion. {@code fieldMicrotesla} es el campo medio de su única captura
     * {@code [start, readingTo)}: la contribución que calcula Personal Dosimetry es
     * {@code campo × segundos}, exacta, así que {@link #expectedIndex()} es el índice final.
     */
    record PlannedSession(int worker, LocalDate day, Instant start, Instant end, Instant readingTo,
                          BigDecimal fieldMicrotesla) {

        BigDecimal expectedIndex() {
            return fieldMicrotesla.multiply(BigDecimal.valueOf(Duration.between(start, readingTo).getSeconds()));
        }

        /**
         * Identidad de la captura en Personal Dosimetry (con el dispositivo y la secuencia 1). Depende
         * del inicio, no del día: dos jornadas abiertas el mismo día en arranques distintos no chocan.
         */
        String bootId() {
            return "af-demo-" + start.getEpochSecond() + "-w" + (worker + 1);
        }
    }

    private DemoExposurePlan() {
    }

    static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZONE);
    }

    static Instant historyStart(Instant now) {
        return today(now).minusDays(HISTORY_DAYS).atStartOfDay(ZONE).toInstant();
    }

    /** Las 60 jornadas cerradas, en orden cronológico. */
    static List<PlannedSession> history(Instant now) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = today(now).minusDays(HISTORY_DAYS); d.isBefore(today(now)); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
        }
        List<PlannedSession> sessions = new ArrayList<>();
        for (int k = 0; k < days.size(); k++) {
            LocalDate day = days.get(k);
            Instant start = day.atTime(SHIFT_START).atZone(ZONE).toInstant();
            Instant end = start.plus(SHIFT);
            for (int w = 0; w < WORKERS.size(); w++) {
                Instant readingTo = (w == INCOMPLETE_WORKER && k == INCOMPLETE_DAY) ? end.minus(UNCOVERED) : end;
                BigDecimal target = LIMIT_INDEX.multiply(BigDecimal.valueOf(RATIOS[w][k]));
                sessions.add(new PlannedSession(w, day, start, end, readingTo, field(target, SHIFT)));
            }
        }
        return sessions;
    }

    /** Jornada abierta de hoy de un operario: empezó hace 4 h y su captura termina hace 1 min. */
    static PlannedSession open(int worker, Instant now) {
        Instant readingTo = now.truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);
        Instant start = readingTo.plusSeconds(60).minus(OPEN_ELAPSED);
        Duration covered = Duration.between(start, readingTo);
        return new PlannedSession(worker, today(now), start, null, readingTo,
                field(new BigDecimal(OPEN_INDEX[worker]), covered));
    }

    /** Campo medio en uT, con los 6 decimales que admite Personal Dosimetry. */
    static BigDecimal field(BigDecimal targetIndex, Duration duration) {
        return targetIndex.divide(BigDecimal.valueOf(duration.getSeconds()), 6, RoundingMode.HALF_UP);
    }
}
