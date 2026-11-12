package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Promedio y máximo de una zona en el período (informe §5.6.1, regla 9).
 *
 * Es lo único que puede contener un reporte por zona: cifras agregadas sin
 * ningún identificador de operario. Que no exista aquí un campo para una
 * persona no es un olvido — es la forma de que RF32 no se pueda incumplir por
 * descuido al añadir una entrada.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ZoneAggregate {

    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "zone_average_value", precision = 12, scale = 4)
    private BigDecimal averageValue;

    @Column(name = "zone_max_value", precision = 12, scale = 4)
    private BigDecimal maxValue;

    @Column(name = "zone_reading_count")
    private Integer readingCount;

    public static ZoneAggregate of(Long zoneId, BigDecimal average, BigDecimal max, int readings) {
        if (zoneId == null) {
            throw new MitigationRuleException("A zone aggregate requires a zone id");
        }
        return new ZoneAggregate(zoneId, average, max, readings);
    }

    public boolean isEmpty() {
        return readingCount == null || readingCount == 0;
    }
}
