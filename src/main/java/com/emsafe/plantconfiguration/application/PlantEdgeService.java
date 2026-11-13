package com.emsafe.plantconfiguration.application;

import com.emsafe.plantconfiguration.domain.model.PlantConfigurationRuleException;
import com.emsafe.plantconfiguration.domain.model.PlantEdge;
import com.emsafe.plantconfiguration.domain.model.PlantZone;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantEdgeRepository;
import com.emsafe.plantconfiguration.infrastructure.persistence.PlantZoneRepository;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantEdgeDto;
import com.emsafe.plantconfiguration.interfaces.rest.dto.PlantEdgeRequest;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Casos de uso del edge de una zona.
 *
 * <p>Recordatorio de por qué esto es configuración y no telemetría: el edge del
 * segmento industrial todavía no existe como software. Lo que se guarda aquí es
 * la <b>declaración</b> del SSO —"esta zona la sirve el edge con este serial"—,
 * no un registro que el edge haga de sí mismo.
 */
@Service
@RequiredArgsConstructor
public class PlantEdgeService {

    private final PlantEdgeRepository edges;
    private final PlantZoneRepository zones;

    @Transactional(readOnly = true)
    public List<PlantEdgeDto> listFor(Long organizationId) {
        List<PlantZone> orgZones = zones.findByOrganizationIdOrderByNameAsc(organizationId);
        if (orgZones.isEmpty()) {
            return List.of();
        }
        List<Long> zoneIds = orgZones.stream().map(PlantZone::getId).toList();
        return edges.findByPlantZoneIdIn(zoneIds).stream()
                .map(edge -> toDto(edge, nameOf(orgZones, edge.getPlantZoneId())))
                .toList();
    }

    @Transactional
    public PlantEdgeDto declare(Long organizationId, PlantEdgeRequest req) {
        PlantZone zone = requireZone(organizationId, req.plantZoneId());

        if (edges.existsByPlantZoneId(zone.getId())) {
            throw new PlantConfigurationRuleException(
                    "Zone '" + zone.getName() + "' already has an edge. "
                            + "A zone is served by exactly one edge");
        }
        String serial = req.serialNumber() == null ? null : req.serialNumber().trim();
        if (serial != null && edges.existsBySerialNumber(serial)) {
            throw new PlantConfigurationRuleException(
                    "An edge with serial '" + serial + "' is already declared");
        }
        PlantEdge edge = PlantEdge.declare(zone.getId(), req.serialNumber());
        return toDto(edges.save(edge), zone.getName());
    }

    @Transactional
    public PlantEdgeDto updateSerial(Long organizationId, Long id, String newSerial) {
        PlantEdge edge = requireEdge(organizationId, id);
        String serial = newSerial == null ? null : newSerial.trim();
        if (serial != null && edges.existsBySerialNumberAndIdNot(serial, id)) {
            throw new PlantConfigurationRuleException(
                    "An edge with serial '" + serial + "' is already declared");
        }
        edge.changeSerial(newSerial);
        PlantZone zone = zones.findById(edge.getPlantZoneId()).orElseThrow();
        return toDto(edges.save(edge), zone.getName());
    }

    @Transactional
    public PlantEdgeDto setActive(Long organizationId, Long id, boolean active) {
        PlantEdge edge = requireEdge(organizationId, id);
        if (active) {
            edge.activate();
        } else {
            edge.deactivate();
        }
        PlantZone zone = zones.findById(edge.getPlantZoneId()).orElseThrow();
        return toDto(edges.save(edge), zone.getName());
    }

    @Transactional
    public void delete(Long organizationId, Long id) {
        edges.delete(requireEdge(organizationId, id));
    }

    /**
     * El edge no guarda la organización: la hereda de su zona. Comprobarlo así
     * evita que los dos datos puedan discrepar.
     */
    private PlantEdge requireEdge(Long organizationId, Long id) {
        PlantEdge edge = edges.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Edge not found with id: " + id));
        requireZone(organizationId, edge.getPlantZoneId());
        return edge;
    }

    private PlantZone requireZone(Long organizationId, Long zoneId) {
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

    private PlantEdgeDto toDto(PlantEdge edge, String zoneName) {
        return new PlantEdgeDto(
                edge.getId(),
                edge.getPlantZoneId(),
                zoneName,
                edge.getSerialNumber(),
                edge.getStatus(),
                edge.getCreatedAt(),
                edge.getConnectivity(),
                edge.getConnectivityChangedAt());
    }

    /**
     * Conectividad que informa el Edge por MQTT (vía Device Management). Un
     * serial no declarado se ignora: el Edge puede existir antes de declararse.
     */
    @Transactional
    public boolean recordConnectivity(String serialNumber, boolean online, java.time.Instant at) {
        if (serialNumber == null) return false;
        return edges.findBySerialNumber(serialNumber.trim()).map(edge -> {
            boolean changed = edge.recordConnectivity(online, at);
            edges.save(edge);
            return changed;
        }).orElse(false);
    }
}
