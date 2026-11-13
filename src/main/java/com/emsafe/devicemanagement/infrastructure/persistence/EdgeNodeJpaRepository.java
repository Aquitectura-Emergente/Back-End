package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.EdgeNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EdgeNodeJpaRepository extends JpaRepository<EdgeNode, Long> {
    Optional<EdgeNode> findByEdgeNodeId(String edgeNodeId);
}
