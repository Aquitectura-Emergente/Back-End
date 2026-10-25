package com.emsafe.radiationmonitoring.domain.services;

import com.emsafe.radiationmonitoring.domain.model.RadiationReading;

/**
 * Smart-edge classification helper — single source of truth for radiation levels.
 *
 * The EDGE is the layer that classifies every reading (SAFE/CAUTION/DANGER in µT,
 * thresholds 100/200, ICNIRP 50 Hz reference). The backend must TRUST that level
 * and never re-classify; the fallback by value here exists only for readings that
 * never went through the edge (seed/legacy data stored with level = NULL).
 */
public final class RadiationLevel {

    /** µT value at/above which a reading is "caution" (fallback only). */
    public static final double CAUTION_UT = 100;
    /** µT value at/above which a reading is "danger" (fallback only). */
    public static final double DANGER_UT = 200;

    private RadiationLevel() {
    }

    /**
     * Returns the traffic-light level of a reading, trusting the edge-computed
     * level (normalized to lowercase). Falls back to the µT thresholds only when
     * the reading carries no valid level.
     */
    public static String of(RadiationReading r) {
        if (r == null) return "safe";
        String lvl = r.getLevel();
        if (lvl != null) {
            String normalized = lvl.trim().toLowerCase();
            if (normalized.equals("safe") || normalized.equals("caution") || normalized.equals("danger")) {
                return normalized;
            }
        }
        return r.getValue() != null ? byValue(r.getValue()) : "safe";
    }

    /** Fallback classification by µT value — for seed/legacy data without level. */
    public static String byValue(double value) {
        if (value < CAUTION_UT) return "safe";
        if (value < DANGER_UT) return "caution";
        return "danger";
    }

    /** Returns the worse (more severe) of two levels. */
    public static String worse(String a, String b) {
        return severity(b) > severity(a) ? b : a;
    }

    private static int severity(String level) {
        return switch (level) {
            case "danger" -> 2;
            case "caution" -> 1;
            default -> 0;
        };
    }
}
