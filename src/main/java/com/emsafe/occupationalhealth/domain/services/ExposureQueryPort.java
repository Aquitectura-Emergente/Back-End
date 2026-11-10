package com.emsafe.occupationalhealth.domain.services;

import com.emsafe.occupationalhealth.domain.model.DoseSnapshot;

/**
 * Consulta a Personal Dosimetry la exposición acumulada vigente de un operario
 * (informe §5.6.1).
 *
 * El puerto existe para que el dominio no dependa de cómo se obtiene la cifra.
 * Personal Dosimetry todavía no tiene implementación, así que el adaptador
 * actual la deriva de las lecturas que ya existen — ver
 * DosimetryExposureAdapter, que documenta exactamente qué parte es provisional.
 */
public interface ExposureQueryPort {

    /**
     * Acumulado vigente del operario frente a su límite aplicable.
     *
     * Nunca devuelve null: si no hay datos, devuelve una foto vacía
     * ({@link DoseSnapshot#isUnknown()}), para que la falta de información sea
     * un dato explícito y no una excepción que el dominio tenga que atrapar.
     */
    DoseSnapshot currentExposure(Long organizationId, Long workerId);
}
