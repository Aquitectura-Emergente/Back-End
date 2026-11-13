package com.emsafe.devicemanagement.domain.repositories;

import com.emsafe.devicemanagement.domain.model.EdgeNode;

import java.util.List;
import java.util.Optional;

/** Guardar un nodo publica sus eventos pendientes en el outbox. */
public interface EdgeNodeRepository {
    EdgeNode save(EdgeNode node);

    Optional<EdgeNode> findByEdgeNodeId(String edgeNodeId);

    List<EdgeNode> findAll();
}
