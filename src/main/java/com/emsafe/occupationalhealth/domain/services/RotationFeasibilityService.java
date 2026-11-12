package com.emsafe.occupationalhealth.domain.services;

import com.emsafe.occupationalhealth.domain.model.AffectedWorker;
import com.emsafe.occupationalhealth.domain.model.DoseSnapshot;
import com.emsafe.occupationalhealth.domain.model.MitigationAction;
import com.emsafe.occupationalhealth.domain.model.ZoneRiskLevel;

import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Verifica que una medida sea aplicable: que quien se mueve tenga margen y que
 * la zona destino lo admita (informe §5.6.1).
 *
 * Es un servicio de dominio y no un método del agregado porque necesita datos
 * de dos contextos ajenos —la exposición de Dosimetry y la clasificación de
 * Radiation Monitoring— que el agregado no debe ir a buscar. Las reglas en sí
 * siguen viviendo donde corresponde: el umbral del 80 % lo decide
 * ZoneRiskLevel.admits() y el margen lo decide DoseSnapshot.
 *
 * Recibe funciones y no mapas ni repositorios para quedar libre de
 * infraestructura y poder probarse sin levantar nada.
 */
@Component
public class RotationFeasibilityService {

    /**
     * Evalúa la medida con la exposición y las clasificaciones de ahora.
     *
     * Una reprogramación es viable por definición: no mueve a nadie, así que no
     * hay zona que validar ni margen que consumir.
     */
    public FeasibilityResult evaluate(MitigationAction action,
                                      Function<Long, DoseSnapshot> exposures,
                                      Function<Long, ZoneRiskLevel> zoneRisks) {
        if (!action.getType().movesWorker()) {
            return FeasibilityResult.feasible();
        }
        if (action.getAffectedWorkers().isEmpty()) {
            return FeasibilityResult.notViable("The action has no affected workers");
        }

        for (AffectedWorker worker : action.getAffectedWorkers()) {
            DoseSnapshot current = exposures.apply(worker.getWorkerId());

            if (current != null && !current.hasMargin()) {
                return FeasibilityResult.notViable(
                        "Worker " + worker.getWorkerId()
                                + " has no margin left against the applicable limit");
            }

            ZoneRiskLevel target = zoneRisks.apply(worker.getToZoneId());
            if (target == null) {
                return FeasibilityResult.notViable(
                        "Target zone " + worker.getToZoneId() + " has no current classification");
            }
            if (!target.admits(current)) {
                return FeasibilityResult.notViable(
                        target.rejectionReason() + " (worker " + worker.getWorkerId() + ")");
            }
        }
        return FeasibilityResult.feasible();
    }
}
