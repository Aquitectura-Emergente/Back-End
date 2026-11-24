package com.emsafe.devicemanagement.infrastructure.persistence;

import com.emsafe.devicemanagement.domain.model.OrganizationCode;
import com.emsafe.devicemanagement.domain.repositories.OrganizationCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaOrganizationCodeRepository implements OrganizationCodeRepository {
    private final OrganizationCodeJpaRepository codes;
    private final Clock clock;

    @Override
    public Optional<Long> resolve(OrganizationCode code) {
        return codes.findById(code.value()).map(OrganizationCodeEntity::getOrganizationId);
    }

    @Override
    public Optional<OrganizationCode> codeOf(Long organizationId) {
        return codes.findByOrganizationId(organizationId).map(c -> new OrganizationCode(c.getCode()));
    }

    @Override
    public void assign(OrganizationCode code, Long organizationId) {
        codes.save(new OrganizationCodeEntity(code.value(), organizationId, clock.instant()));
    }

    @Override
    public Map<String, Long> findAll() {
        Map<String, Long> all = new LinkedHashMap<>();
        codes.findAll(Sort.by("code")).forEach(c -> all.put(c.getCode(), c.getOrganizationId()));
        return all;
    }
}
