package com.emsafe.devicemanagement.infrastructure.acl;

import com.emsafe.devicemanagement.application.ports.PlantEdgeGateway;
import com.emsafe.plantconfiguration.application.PlantEdgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Capa anticorrupción hacia Plant Configuration. */
@Component
@RequiredArgsConstructor
public class PlantConfigurationEdgeAdapter implements PlantEdgeGateway {
    private final PlantEdgeService plantEdges;

    @Override
    public void recordConnectivity(String edgeNodeId, boolean online, Instant at) {
        plantEdges.recordConnectivity(edgeNodeId, online, at);
    }
}
