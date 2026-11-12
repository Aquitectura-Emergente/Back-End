package com.emsafe.occupationalhealth.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regla 3 del informe §5.6.1: una zona RISK no recibe a nadie, y una CAUTION
 * solo admite a quien lleve consumido menos del 80 % de su límite — el mismo
 * umbral de advertencia de US59.
 *
 * Cubre también las invariantes de DoseSnapshot, de donde sale el porcentaje.
 */
class ZoneAdmissionTest {

    private static DoseSnapshot consuming(String value, String limit) {
        return DoseSnapshot.of(new BigDecimal(value), "uT.h", "TEST", new BigDecimal(limit));
    }

    // ── Admisión por zona ────────────────────────────────────────────────────

    @Test
    @DisplayName("una zona segura admite a cualquiera")
    void safeZoneAdmitsAnyone() {
        assertTrue(ZoneRiskLevel.SAFE.admits(consuming("799", "800")));
        assertTrue(ZoneRiskLevel.SAFE.admits(null));
    }

    @Test
    @DisplayName("una zona de riesgo no admite a nadie, ni con el límite intacto")
    void riskZoneAdmitsNobody() {
        assertFalse(ZoneRiskLevel.RISK.admits(consuming("0", "800")));
        assertFalse(ZoneRiskLevel.RISK.admits(null));
    }

    @Test
    @DisplayName("una zona CAUTION admite por debajo del 80 % consumido")
    void cautionZoneAdmitsBelowThreshold() {
        // 630 / 800 = 78.75 %
        assertTrue(ZoneRiskLevel.CAUTION.admits(consuming("630", "800")));
    }

    @Test
    @DisplayName("una zona CAUTION rechaza justo en el 80 %: el umbral es estricto")
    void cautionZoneRejectsAtThreshold() {
        // 640 / 800 = 80.00 % exacto
        assertFalse(ZoneRiskLevel.CAUTION.admits(consuming("640", "800")));
        assertFalse(ZoneRiskLevel.CAUTION.admits(consuming("700", "800")));
    }

    @Test
    @DisplayName("sin dato de exposición, la zona CAUTION no bloquea al SSO")
    void cautionZoneAdmitsWhenExposureUnknown() {
        DoseSnapshot unknown = DoseSnapshot.of(null, "uT.h", "TEST", new BigDecimal("800"));

        assertTrue(ZoneRiskLevel.CAUTION.admits(unknown),
                "la falta de dato de otro contexto no se convierte en una negativa");
        assertTrue(unknown.isUnknown());
    }

    @Test
    @DisplayName("el motivo del rechazo nombra el umbral, para devolverlo en el 422")
    void rejectionReasonMentionsThreshold() {
        assertTrue(ZoneRiskLevel.RISK.rejectionReason().contains("RISK"));
        assertTrue(ZoneRiskLevel.CAUTION.rejectionReason().contains("80"));
    }

    // ── DoseSnapshot ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("el porcentaje se deriva de value y limit, no se recibe")
    void percentageIsDerived() {
        assertEquals(new BigDecimal("25.00"), consuming("200", "800").getPercentage());
        assertEquals(new BigDecimal("100.00"), consuming("800", "800").getPercentage());
    }

    @Test
    @DisplayName("quien llegó al límite ya no tiene margen")
    void marginEndsAtTheLimit() {
        assertTrue(consuming("799", "800").hasMargin());
        assertFalse(consuming("800", "800").hasMargin());
        assertFalse(consuming("900", "800").hasMargin());
    }

    @Test
    @DisplayName("una dosis o un límite negativos se rechazan")
    void negativeValuesAreRejected() {
        assertThrows(MitigationRuleException.class, () -> consuming("-1", "800"));
        assertThrows(MitigationRuleException.class, () -> consuming("100", "-800"));
    }

    @Test
    @DisplayName("un límite de cero no produce un porcentaje infinito, sino ninguno")
    void zeroLimitYieldsNoPercentage() {
        DoseSnapshot snapshot = consuming("100", "0");

        assertNull(snapshot.getPercentage());
        assertTrue(snapshot.hasMargin(), "sin porcentaje conocido no se afirma que falte margen");
    }
}
