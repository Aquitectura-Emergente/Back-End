package com.emsafe.occupationalhealth.domain.model;

import java.math.BigDecimal;

/**
 * Clasificación vigente de una zona de planta, tal como la informa Radiation
 * Monitoring a través de ZoneExposurePort.
 *
 * Es deliberadamente un vocabulario propio de este contexto y no el
 * RadiationLevel del backend: el adaptador traduce. Así el dominio no queda
 * atado a cómo clasifica otro contexto.
 */
public enum ZoneRiskLevel {
    SAFE,
    CAUTION,
    RISK;

    /**
     * Umbral de advertencia de US59: en una zona CAUTION solo entra quien lleve
     * consumido menos del 80 % de su límite (informe §5.6.1, regla 3).
     */
    public static final BigDecimal CAUTION_MAX_CONSUMED_PERCENTAGE = new BigDecimal("80");

    /**
     * Si esta zona admite recibir a un operario con la exposición indicada.
     *
     * La regla vive aquí, junto a los niveles, y no en el servicio: una zona de
     * riesgo no acepta a nadie, una zona segura acepta a cualquiera, y la
     * intermedia depende de cuánto margen le queda a quien llega.
     */
    public boolean admits(DoseSnapshot arriving) {
        return switch (this) {
            case SAFE -> true;
            case RISK -> false;
            case CAUTION -> arriving == null
                    || arriving.consumedPercentageBelow(CAUTION_MAX_CONSUMED_PERCENTAGE);
        };
    }

    /** Motivo legible del rechazo, para devolverlo en el 422. */
    public String rejectionReason() {
        return switch (this) {
            case RISK -> "Target zone is classified as RISK";
            case CAUTION -> "Target zone is classified as CAUTION and the worker has consumed "
                    + CAUTION_MAX_CONSUMED_PERCENTAGE + "% or more of the applicable limit";
            case SAFE -> "Target zone admits the worker";
        };
    }
}
