package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.domain.ports.PositionCatalogPort;
import com.emsafe.plantconfiguration.application.JobPositionService;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Nombres de puesto desde Plant Configuration ({@link JobPositionService#listFor}), sin modificarlo.
 *
 * <p><b>NOT_SUPPORTED</b> a propósito: el resumen corre en una transacción de solo lectura y
 * {@code JobPositionService} es transaccional. Si fallara dentro de nuestra transacción, la
 * marcaría rollback-only y el resumen entero terminaría en 500 aunque aquí se capture la
 * excepción. Fuera de ella, un fallo solo deja los nombres en {@code null}.</p>
 */
@Component
public class PlantPositionCatalogAdapter implements PositionCatalogPort {

    private static final Logger log = LoggerFactory.getLogger(PlantPositionCatalogAdapter.class);

    private final JobPositionService positions;

    public PlantPositionCatalogAdapter(JobPositionService positions) {
        this.positions = positions;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<Long, String> positionNames(Long organizationId) {
        try {
            Map<Long, String> names = new HashMap<>();
            for (JobPositionDto p : positions.listFor(organizationId, null)) {
                if (p != null && p.id() != null && p.name() != null) {
                    names.put(p.id(), p.name());
                }
            }
            return names;
        } catch (RuntimeException e) {
            log.warn("Plant Configuration positions unavailable for organization {}: {}", organizationId,
                    e.toString());
            return Map.of();
        }
    }
}
