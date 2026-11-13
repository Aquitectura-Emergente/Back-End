package com.emsafe.plantconfiguration.application;

import com.emsafe.plantconfiguration.domain.model.JobPosition;
import com.emsafe.plantconfiguration.domain.model.PlantConfigurationRuleException;
import com.emsafe.plantconfiguration.domain.model.PlantZone;
import com.emsafe.plantconfiguration.infrastructure.persistence.JobPositionRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantZoneRepository;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionDto;
import com.emsafe.plantconfiguration.interfaces.rest.dto.JobPositionRequest;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Casos de uso de los puestos de trabajo.
 *
 * <p>Un puesto vive dentro de una zona y hereda de ella la organización, así que
 * el aislamiento entre plantas se comprueba siempre pasando por la zona.
 */
@Service
@RequiredArgsConstructor
public class JobPositionService {

    private final JobPositionRepository positions;
    private final PlantZoneRepository zones;

    @Transactional(readOnly = true)
    public List<JobPositionDto> listFor(Long organizationId, Long plantZoneId) {
        if (plantZoneId != null) {
            PlantZone zone = requireZone(organizationId, plantZoneId);
            return positions.findByPlantZoneIdOrderByNameAsc(zone.getId()).stream()
                    .map(p -> toDto(p, zone.getName()))
                    .toList();
        }
        List<PlantZone> orgZones = zones.findByOrganizationIdOrderByNameAsc(organizationId);
        if (orgZones.isEmpty()) {
            return List.of();
        }
        List<Long> zoneIds = orgZones.stream().map(PlantZone::getId).toList();
        return positions.findByPlantZoneIdInOrderByNameAsc(zoneIds).stream()
                .map(p -> toDto(p, nameOf(orgZones, p.getPlantZoneId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public JobPositionDto getFor(Long organizationId, Long id) {
        JobPosition position = requirePosition(organizationId, id);
        PlantZone zone = zones.findById(position.getPlantZoneId()).orElseThrow();
        return toDto(position, zone.getName());
    }

    @Transactional
    public JobPositionDto create(Long organizationId, JobPositionRequest req) {
        PlantZone zone = requireZone(organizationId, req.plantZoneId());
        assertNameFree(zone, req.name(), null);
        JobPosition position = JobPosition.create(zone.getId(), req.name(), req.description());
        return toDto(positions.save(position), zone.getName());
    }

    @Transactional
    public JobPositionDto update(Long organizationId, Long id, JobPositionRequest req) {
        JobPosition position = requirePosition(organizationId, id);
        // La zona destino también tiene que ser de la misma organización: así un
        // puesto no puede saltar de planta por el camino de una edición.
        PlantZone zone = requireZone(organizationId, req.plantZoneId());
        assertNameFree(zone, req.name(), id);

        position.moveTo(zone.getId());
        position.rename(req.name());
        position.describeAs(req.description());
        return toDto(positions.save(position), zone.getName());
    }

    /**
     * Baja de un puesto.
     *
     * <p>No se comprueba aquí si lo usa un turno: lo impide el
     * {@code ON DELETE RESTRICT} de {@code fk_position_assignments_job_position}.
     * Es deliberado — mirar las asignaciones desde este contexto obligaría a
     * consultar tablas de {@code occupationalhealth}, y esa frontera se respeta.
     * Cuando el puesto esté en uso, la restricción salta en la base de datos.
     */
    @Transactional
    public void delete(Long organizationId, Long id) {
        positions.delete(requirePosition(organizationId, id));
    }

    private void assertNameFree(PlantZone zone, String rawName, Long excludeId) {
        String name = rawName == null ? null : rawName.trim();
        if (name == null || name.isEmpty()) {
            return; // lo valida el agregado con su propio mensaje
        }
        boolean taken = excludeId == null
                ? positions.existsByPlantZoneIdAndName(zone.getId(), name)
                : positions.existsByPlantZoneIdAndNameAndIdNot(zone.getId(), name, excludeId);
        if (taken) {
            throw new PlantConfigurationRuleException(
                    "A job position named '" + name + "' already exists in zone '"
                            + zone.getName() + "'");
        }
    }

    private JobPosition requirePosition(Long organizationId, Long id) {
        JobPosition position = positions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Job position not found with id: " + id));
        requireZone(organizationId, position.getPlantZoneId());
        return position;
    }

    private PlantZone requireZone(Long organizationId, Long zoneId) {
        if (zoneId == null) {
            throw new PlantConfigurationRuleException("plantZoneId is required");
        }
        return zones.findByIdAndOrganizationId(zoneId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Plant zone not found with id: " + zoneId));
    }

    private String nameOf(List<PlantZone> orgZones, Long zoneId) {
        return orgZones.stream()
                .filter(z -> z.getId().equals(zoneId))
                .map(PlantZone::getName)
                .findFirst()
                .orElse(null);
    }

    private JobPositionDto toDto(JobPosition position, String zoneName) {
        return new JobPositionDto(
                position.getId(),
                position.getPlantZoneId(),
                zoneName,
                position.getName(),
                position.getDescription(),
                position.getCreatedAt());
    }
}
