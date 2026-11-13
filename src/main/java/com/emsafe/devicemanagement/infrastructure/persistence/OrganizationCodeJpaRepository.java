package com.emsafe.devicemanagement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationCodeJpaRepository extends JpaRepository<OrganizationCodeEntity, String> {
    Optional<OrganizationCodeEntity> findByOrganizationId(Long organizationId);
}
