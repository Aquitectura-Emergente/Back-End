package com.emsafe.occupationalhealth.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * La exposición acumulada con la que se decidió una medida (informe §5.6.1).
 *
 * Es una foto, no una referencia viva: queda congelada dentro de la acción para
 * que una auditoría pueda ver con qué cifra se decidió, aunque la exposición del
 * operario haya cambiado después. La autoridad del cálculo es Personal
 * Dosimetry; aquí nunca se recalcula, solo se conserva.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DoseSnapshot {

    @Column(name = "dose_value", precision = 12, scale = 4)
    private BigDecimal value;

    @Column(name = "dose_unit", length = 10)
    private String unit;

    /** Método de cálculo aplicado por Dosimetry; viaja para poder contrastarlo. */
    @Column(name = "dose_method_id", length = 50)
    private String methodId;

    @Column(name = "dose_limit", precision = 12, scale = 4)
    private BigDecimal limit;

    @Column(name = "dose_percentage", precision = 6, scale = 2)
    private BigDecimal percentage;

    /**
     * Construye la foto y deriva el porcentaje consumido.
     *
     * Valores negativos se rechazan: una dosis acumulada no puede decrecer y un
     * límite negativo no tiene sentido físico. El porcentaje se calcula aquí y
     * no se recibe, para que no pueda llegar incoherente con value y limit.
     */
    public static DoseSnapshot of(BigDecimal value, String unit, String methodId, BigDecimal limit) {
        if (value != null && value.signum() < 0) {
            throw new MitigationRuleException("Dose value cannot be negative");
        }
        if (limit != null && limit.signum() < 0) {
            throw new MitigationRuleException("Dose limit cannot be negative");
        }
        return new DoseSnapshot(value, unit, methodId, limit, percentageOf(value, limit));
    }

    /**
     * Porcentaje del límite ya consumido, o null si falta algún dato o el límite
     * es cero (dividir por cero no es un porcentaje infinito: es un dato que no
     * tenemos).
     */
    private static BigDecimal percentageOf(BigDecimal value, BigDecimal limit) {
        if (value == null || limit == null || limit.signum() == 0) return null;
        return value.multiply(BigDecimal.valueOf(100))
                .divide(limit, 2, RoundingMode.HALF_UP);
    }

    /**
     * Si el porcentaje consumido está estrictamente por debajo del máximo dado.
     *
     * Cuando no hay porcentaje conocido devuelve true: el contexto no bloquea
     * una decisión del SSO por falta de datos de otro contexto, y la ausencia
     * queda registrada en la propia foto.
     */
    public boolean consumedPercentageBelow(BigDecimal maxPercentage) {
        if (percentage == null || maxPercentage == null) return true;
        return percentage.compareTo(maxPercentage) < 0;
    }

    /**
     * Si al operario le queda margen frente a su límite. Es la comprobación de
     * la regla 4 al aprobar una rotación: un reemplazo sin margen la invalida.
     */
    public boolean hasMargin() {
        return consumedPercentageBelow(BigDecimal.valueOf(100));
    }

    /** Una foto sin cifra: Dosimetry no pudo responder. */
    public boolean isUnknown() {
        return value == null || limit == null;
    }
}
