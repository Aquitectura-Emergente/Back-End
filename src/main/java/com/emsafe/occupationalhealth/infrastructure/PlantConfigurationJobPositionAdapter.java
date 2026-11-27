package com.emsafe.occupationalhealth.infrastructure;

import com.emsafe.occupationalhealth.domain.services.JobPositionZonePort;
import com.emsafe.plantconfiguration.application.JobPositionService;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Consulta la zona de un puesto a través del servicio de aplicación de Plant Configuration. */
@Component
@RequiredArgsConstructor
public class PlantConfigurationJobPositionAdapter implements JobPositionZonePort {

    private final JobPositionService positions;

    @Override
    public Optional<Long> zoneOf(Long organizationId, Long jobPositionId) {
        // Se busca en la lista de la empresa: un puesto que no existe es simplemente
        // vacío, sin excepción que marque la transacción del llamador para deshacerse.
        return positions.listFor(organizationId, null).stream()
                .filter(p -> p.id().equals(jobPositionId))
                .map(JobPositionDto::plantZoneId)
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }
}
