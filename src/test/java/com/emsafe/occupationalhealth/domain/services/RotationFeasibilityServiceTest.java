package com.emsafe.occupationalhealth.domain.services;

import com.emsafe.occupationalhealth.domain.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regla 4 del informe §5.6.1: al aprobar se vuelve a consultar la exposición de
 * los dos operarios, porque la sugerencia del agente pudo quedar
 * desactualizada. Si el reemplazo ya no tiene margen, la medida no es viable.
 *
 * El servicio recibe funciones, así que se prueba sin levantar nada.
 */
class RotationFeasibilityServiceTest {

    private static final Long ORG = 7L;

    private final RotationFeasibilityService service = new RotationFeasibilityService();

    private static DoseSnapshot dose(String value) {
        return DoseSnapshot.of(new BigDecimal(value), "uT.h", "TEST", new BigDecimal("800"));
    }

    private static MitigationAction rotationBetween(Long workerA, Long workerB) {
        MitigationAction action = MitigationAction.propose(ORG, MitigationType.ROTATION,
                MitigationOrigin.AI_SUGGESTION,
                MitigationTrigger.fromEvent("evt-1", TriggerKind.ROTATION_SUGGESTED, null),
                "proyección");
        action.addAffectedWorker(workerA, 10L, 20L, 200L, dose("700"));
        action.addAffectedWorker(workerB, 20L, 10L, 100L, dose("100"));
        return action;
    }

    @Test
    @DisplayName("con margen en ambos y zonas seguras, la rotación es viable")
    void feasibleWhenBothHaveMarginAndZonesAreSafe() {
        Map<Long, DoseSnapshot> exposures = Map.of(1L, dose("700"), 2L, dose("100"));

        FeasibilityResult result = service.evaluate(rotationBetween(1L, 2L),
                exposures::get, zone -> ZoneRiskLevel.SAFE);

        assertTrue(result.viable());
        assertNull(result.reason());
    }

    @Test
    @DisplayName("si el reemplazo ya no tiene margen, deja de ser viable")
    void notViableWhenReplacementRanOutOfMargin() {
        // El operario 2 era el reemplazo con margen; ahora está en el límite.
        Map<Long, DoseSnapshot> exposures = Map.of(1L, dose("700"), 2L, dose("800"));

        FeasibilityResult result = service.evaluate(rotationBetween(1L, 2L),
                exposures::get, zone -> ZoneRiskLevel.SAFE);

        assertTrue(result.isNotViable());
        assertTrue(result.reason().contains("no margin left"));
    }

    @Test
    @DisplayName("una zona destino de riesgo invalida la rotación")
    void notViableTowardsRiskZone() {
        Map<Long, DoseSnapshot> exposures = Map.of(1L, dose("100"), 2L, dose("100"));

        FeasibilityResult result = service.evaluate(rotationBetween(1L, 2L),
                exposures::get, zone -> ZoneRiskLevel.RISK);

        assertTrue(result.isNotViable());
        assertTrue(result.reason().contains("RISK"));
    }

    @Test
    @DisplayName("una zona CAUTION rechaza a quien pasa del 80 % consumido")
    void notViableTowardsCautionZoneWithHighConsumption() {
        // 700 / 800 = 87.5 %
        Map<Long, DoseSnapshot> exposures = Map.of(1L, dose("700"), 2L, dose("100"));

        FeasibilityResult result = service.evaluate(rotationBetween(1L, 2L),
                exposures::get, zone -> ZoneRiskLevel.CAUTION);

        assertTrue(result.isNotViable());
        assertTrue(result.reason().contains("CAUTION"));
    }

    @Test
    @DisplayName("una zona sin clasificación vigente no es destino válido")
    void notViableWhenZoneHasNoClassification() {
        Map<Long, DoseSnapshot> exposures = Map.of(1L, dose("100"), 2L, dose("100"));

        FeasibilityResult result = service.evaluate(rotationBetween(1L, 2L),
                exposures::get, zone -> null);

        assertTrue(result.isNotViable());
        assertTrue(result.reason().contains("no current classification"));
    }

    @Test
    @DisplayName("una reprogramación es viable por definición: no mueve a nadie")
    void reschedulingIsAlwaysFeasible() {
        MitigationAction rescheduling = MitigationAction.propose(ORG,
                MitigationType.RESCHEDULING, MitigationOrigin.MANUAL,
                MitigationTrigger.manual(), "sin candidato");

        FeasibilityResult result = service.evaluate(rescheduling,
                id -> dose("800"), zone -> ZoneRiskLevel.RISK);

        assertTrue(result.viable(), "ni el margen ni la zona aplican si nadie se mueve");
    }

    @Test
    @DisplayName("una medida que mueve gente pero sin operarios no es viable")
    void notViableWithoutAffectedWorkers() {
        MitigationAction empty = MitigationAction.propose(ORG, MitigationType.RELOCATION,
                MitigationOrigin.MANUAL, MitigationTrigger.manual(), null);

        FeasibilityResult result = service.evaluate(empty,
                id -> dose("100"), zone -> ZoneRiskLevel.SAFE);

        assertTrue(result.isNotViable());
        assertTrue(result.reason().contains("no affected workers"));
    }
}
