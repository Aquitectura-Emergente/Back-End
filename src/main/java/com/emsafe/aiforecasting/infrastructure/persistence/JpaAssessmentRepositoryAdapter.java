package com.emsafe.aiforecasting.infrastructure.persistence;

import com.emsafe.aiforecasting.domain.model.AssessmentStatus;
import com.emsafe.aiforecasting.domain.model.PreventiveAssessment;
import com.emsafe.aiforecasting.domain.ports.AssessmentRepository;
import com.emsafe.aiforecasting.domain.ports.AssessmentSearchCriteria;
import com.emsafe.aiforecasting.domain.ports.PageResult;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class JpaAssessmentRepositoryAdapter implements AssessmentRepository {

    private final AssessmentJpaRepository jpa;

    public JpaAssessmentRepositoryAdapter(AssessmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    /**
     * Se sincroniza en el acto (flush) para que, al superseder y crear en la misma
     * transacción, el UPDATE que libera active_session_key llegue antes del INSERT
     * que lo vuelve a ocupar. Hibernate, por defecto, ejecuta los INSERT primero.
     */
    @Override
    public PreventiveAssessment save(PreventiveAssessment assessment) {
        PreventiveAssessmentEntity entity = jpa.findById(assessment.getId())
                .orElseGet(() -> new PreventiveAssessmentEntity(assessment.getId()));
        entity.copyFrom(assessment);
        jpa.saveAndFlush(entity);
        return assessment;
    }

    @Override
    public Optional<PreventiveAssessment> findById(String id) {
        return jpa.findById(id).map(PreventiveAssessmentEntity::toDomain);
    }

    @Override
    public Optional<PreventiveAssessment> findActiveBySessionId(String sessionId) {
        return jpa.findByActiveSessionKey(sessionId).map(PreventiveAssessmentEntity::toDomain);
    }

    @Override
    public Optional<PreventiveAssessment> findActiveByWorkerId(Long workerId) {
        return jpa.findFirstByWorkerIdAndStatusNotOrderByAssessedAtDesc(workerId, AssessmentStatus.CLOSED)
                .map(PreventiveAssessmentEntity::toDomain);
    }

    @Override
    public List<PreventiveAssessment> findActiveByOrganizationId(Long organizationId) {
        return jpa.findByOrganizationIdAndStatusNot(organizationId, AssessmentStatus.CLOSED).stream()
                .map(PreventiveAssessmentEntity::toDomain)
                .toList();
    }

    @Override
    public PageResult<PreventiveAssessment> search(AssessmentSearchCriteria criteria, int page, int size) {
        Page<PreventiveAssessmentEntity> result = jpa.findAll(matching(criteria),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("assessedAt"), Sort.Order.asc("id"))));
        return new PageResult<>(result.map(PreventiveAssessmentEntity::toDomain).getContent(),
                page, size, result.getTotalElements());
    }

    private static Specification<PreventiveAssessmentEntity> matching(AssessmentSearchCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (c.organizationId() != null) {
                predicates.add(cb.equal(root.get("organizationId"), c.organizationId()));
            }
            if (c.workerId() != null) {
                predicates.add(cb.equal(root.get("workerId"), c.workerId()));
            }
            if (c.sessionId() != null) {
                predicates.add(cb.equal(root.get("sessionId"), c.sessionId()));
            }
            if (c.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("assessedAt"), c.from()));
            }
            if (c.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("assessedAt"), c.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
