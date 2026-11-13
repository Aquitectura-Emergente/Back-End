package com.emsafe.occupationalhealth.interfaces.rest.dto;

import com.emsafe.occupationalhealth.domain.model.DoseSnapshot;

import java.math.BigDecimal;

/**
 * La exposición con la que se decidió la medida.
 *
 * methodId viaja siempre: es lo que permite a quien lee el dato saber que hoy
 * la cifra la produce un adaptador provisional y no Personal Dosimetry.
 */
public record DoseSnapshotResource(
        BigDecimal value,
        String unit,
        String methodId,
        BigDecimal limit,
        BigDecimal percentage
) {
    public static DoseSnapshotResource from(DoseSnapshot d) {
        if (d == null) return null;
        return new DoseSnapshotResource(d.getValue(), d.getUnit(), d.getMethodId(),
                d.getLimit(), d.getPercentage());
    }
}
