package com.emsafe.aiforecasting.application.fakes;

import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.AssessmentSearchCriteria;
import com.emsafe.aiforecasting.domain.ports.PageResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class InMemoryAssessmentRepository implements AssessmentRepository {

    private final Map<String, PreventiveAssessment> store = new LinkedHashMap<>();

    @Override
    public PreventiveAssessment save(PreventiveAssessment assessment) {
        store.put(assessment.getId(), assessment);
        return assessment;
    }

    @Override
    public Optional<PreventiveAssessment> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<PreventiveAssessment> findActiveBySessionId(String sessionId) {
        return store.values().stream()
                .filter(PreventiveAssessment::isActive)
                .filter(a -> a.getSessionId().equals(sessionId))
                .findFirst();
    }

    @Override
    public Optional<PreventiveAssessment> findActiveByWorkerId(Long workerId) {
        return store.values().stream()
                .filter(PreventiveAssessment::isActive)
                .filter(a -> a.getWorkerId().equals(workerId))
                .max(Comparator.comparing(PreventiveAssessment::getAssessedAt));
    }

    @Override
    public List<PreventiveAssessment> findActiveByOrganizationId(Long organizationId) {
        return store.values().stream()
                .filter(PreventiveAssessment::isActive)
                .filter(a -> a.getOrganizationId().equals(organizationId))
                .toList();
    }

    @Override
    public PageResult<PreventiveAssessment> search(AssessmentSearchCriteria c, int page, int size) {
        List<PreventiveAssessment> matches = store.values().stream()
                .filter(a -> c.organizationId() == null || Objects.equals(a.getOrganizationId(), c.organizationId()))
                .filter(a -> c.workerId() == null || Objects.equals(a.getWorkerId(), c.workerId()))
                .filter(a -> c.sessionId() == null || Objects.equals(a.getSessionId(), c.sessionId()))
                .filter(a -> c.from() == null || !a.getAssessedAt().isBefore(c.from()))
                .filter(a -> c.to() == null || !a.getAssessedAt().isAfter(c.to()))
                .sorted(Comparator.comparing(PreventiveAssessment::getAssessedAt).reversed())
                .toList();
        int from = Math.min(page * size, matches.size());
        int to = Math.min(from + size, matches.size());
        return new PageResult<>(new ArrayList<>(matches.subList(from, to)), page, size, matches.size());
    }

    public List<PreventiveAssessment> all() {
        return List.copyOf(store.values());
    }
}
