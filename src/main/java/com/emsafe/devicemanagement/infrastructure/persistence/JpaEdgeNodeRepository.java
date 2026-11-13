package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.EdgeNode;
import com.emsafe.devicemanagement.domain.repositories.DomainEventOutbox;
import com.emsafe.devicemanagement.domain.repositories.EdgeNodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaEdgeNodeRepository implements EdgeNodeRepository {
    private final EdgeNodeJpaRepository nodes;
    private final DomainEventOutbox outbox;

    @Override
    public EdgeNode save(EdgeNode node) {
        EdgeNode saved = nodes.save(node);
        node.pullEvents().forEach(outbox::append);
        return saved;
    }

    @Override
    public Optional<EdgeNode> findByEdgeNodeId(String edgeNodeId) {
        return nodes.findByEdgeNodeId(edgeNodeId);
    }

    @Override
    public List<EdgeNode> findAll() {
        return nodes.findAll();
    }
}
