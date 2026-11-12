package com.emsafe.plantconfiguration.application;

import com.emsafe.iottelemetry.infrastructure.persistence.DeviceRepository;
import com.emsafe.plantconfiguration.domain.model.PlantConfigurationRuleException;
import com.emsafe.plantconfiguration.domain.model.PlantEdge;
import com.emsafe.plantconfiguration.domain.model.PlantZone;
import com.emsafe.plantconfiguration.infrastructure.persistence.JobPositionRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantEdgeRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantZoneRepository;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantZoneDto;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantZoneRequest;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Casos de uso de las zonas de una planta.
 *
 * <p>Todas las operaciones están acotadas a la organización de quien llama: el
 * id de organización lo pone la política de acceso, nunca el cuerpo de la
 * petición. Así un SSO no puede crear ni leer zonas de otra planta aunque
 * acierte un id.
 */
@Service
@RequiredArgsConstructor
public class PlantZoneService {

    private final PlantZoneRepository zones;
    private final PlantEdgeRepository edges;
    private final JobPositionRepository positions;
    private final DeviceRepository devices;

    @Transactional(readOnly = true)
    public List<PlantZoneDto> listFor(Long organizationId) {
        return zones.findByOrganizationIdOrderByNameAsc(organizationId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PlantZoneDto getFor(Long organizationId, Long id) {
        return toDto(requireZone(organizationId, id));
    }

    @Transactional
    public PlantZoneDto create(Long organizationId, PlantZoneRequest req) {
        String name = req.name() == null ? null : req.name().trim();
        if (name != null && zones.existsByOrganizationIdAndName(organizationId, name)) {
            throw new PlantConfigurationRuleException(
                    "A plant zone named '" + name + "' already exists in this organization");
        }
        PlantZone zone = PlantZone.create(organizationId, req.name(), req.description());
        return toDto(zones.save(zone));
    }

    @Transactional
    public PlantZoneDto update(Long organizationId, Long id, PlantZoneRequest req) {
        PlantZone zone = requireZone(organizationId, id);
        String name = req.name() == null ? null : req.name().trim();
        if (name != null
                && zones.existsByOrganizationIdAndNameAndIdNot(organizationId, name, id)) {
            throw new PlantConfigurationRuleException(
                    "A plant zone named '" + name + "' already exists in this organization");
        }
        zone.rename(req.name());
        zone.describeAs(req.description());
        return toDto(zones.save(zone));
    }

    /**
     * Baja de una zona.
     *
     * <p>Se comprueba a mano que esté vacía en vez de dejar que salte la clave
     * ajena: el {@code ON DELETE RESTRICT} de la V22 protegería la integridad,
     * pero devolvería un 500 con un error de MySQL. Aquí el SSO recibe un 400
     * que dice exactamente qué le estorba.
     */
    @Transactional
    public void delete(Long organizationId, Long id) {
        PlantZone zone = requireZone(organizationId, id);

        if (edges.existsByPlantZoneId(id)) {
            throw new PlantConfigurationRuleException(
                    "This zone still has an edge declared. Remove the edge first");
        }
        long positionCount = positions.countByPlantZoneId(id);
        if (positionCount > 0) {
            throw new PlantConfigurationRuleException(
                    "This zone still has " + positionCount
                            + " job position(s). Remove or move them first");
        }
        long deviceCount = devices.countByPlantZoneId(id);
        if (deviceCount > 0) {
            throw new PlantConfigurationRuleException(
                    "This zone still has " + deviceCount
                            + " device(s) assigned. Reassign them first");
        }
        zones.delete(zone);
    }

    /** Falla con 404 si la zona no existe O si es de otra organización. */
    private PlantZone requireZone(Long organizationId, Long id) {
        return zones.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Plant zone not found with id: " + id));
    }

    private PlantZoneDto toDto(PlantZone zone) {
        Optional<PlantEdge> edge = edges.findByPlantZoneId(zone.getId());
        return new PlantZoneDto(
                zone.getId(),
                zone.getOrganizationId(),
                zone.getName(),
                zone.getDescription(),
                zone.getCreatedAt(),
                edge.map(PlantEdge::getSerialNumber).orElse(null),
                edge.map(PlantEdge::getStatus).orElse(null),
                devices.countByPlantZoneId(zone.getId()),
                positions.countByPlantZoneId(zone.getId()));
    }
}
