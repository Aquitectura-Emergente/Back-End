package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.occupationalhealth.domain.model.DoseSnapshot;
import com.emsafe.occupationalhealth.domain.services.ExposureQueryPort;
import com.emsafe.personaldosimetry.application.ExposureQueryFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Implementa ExposureQueryPort contra Personal Dosimetry (informe §5.6.4).
 *
 * <p>Reemplaza, desde el 2026-11-22, al {@code DosimetryExposureAdapter}
 * provisional, que estimaba una «dosis» en {@code uT.h} con el sensor de la zona
 * y un límite de relleno de 800. Aquel tenía además un defecto: buscaba las
 * lecturas pasando el id de la <b>zona</b> como id del <b>dispositivo</b>, así
 * que la cifra salía de un sensor cualquiera o de ninguno. Ahora la cifra y el
 * límite son los de la jornada abierta del operario, en {@code uT*s}, que es la
 * unidad canónica de todo el sistema.</p>
 *
 * <p>Capa anticorrupción: el dominio de este contexto solo ve un
 * {@link DoseSnapshot}; nunca una sesión de dosimetría.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalDosimetryExposureAdapter implements ExposureQueryPort {

    /** Unidad que se informa cuando no hay cifra: la misma que la de una foto conocida. */
    static final String UNIT = "uT*s";
    /** Método que se informa cuando no hay cifra (el de Personal Dosimetry). */
    static final String METHOD_ID = "MAGNETIC_TIME_INDEX_V1";
    /** Las columnas dose_value y dose_limit son DECIMAL(12,4). */
    private static final int SCALE = 4;

    private final ExposureQueryFacade dosimetry;

    @Override
    public DoseSnapshot currentExposure(Long organizationId, Long workerId) {
        return dosimetry.currentExposure(organizationId, workerId)
                .map(e -> DoseSnapshot.of(scaled(e.accumulatedIndex()), e.unit(),
                        e.methodId(), scaled(e.limitIndex())))
                .orElseGet(() -> {
                    log.debug("Worker {} has no open exposure session; exposure unknown", workerId);
                    return DoseSnapshot.of(null, UNIT, METHOD_ID, null);
                });
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
