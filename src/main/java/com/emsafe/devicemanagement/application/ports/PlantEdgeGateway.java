package com.emsafe.devicemanagement.application.ports;

import java.time.Instant;

/** Plant Configuration: conectividad de los Edges de planta. */
public interface PlantEdgeGateway {
    void recordConnectivity(String edgeNodeId, boolean online, Instant at);
}
