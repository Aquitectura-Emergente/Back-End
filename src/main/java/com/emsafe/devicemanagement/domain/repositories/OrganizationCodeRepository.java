package com.emsafe.devicemanagement.domain.repositories;

import com.emsafe.devicemanagement.domain.model.OrganizationCode;

import java.util.Optional;

/** Traduce el código que envían los Edges al id numérico de la organización. */
public interface OrganizationCodeRepository {
    Optional<Long> resolve(OrganizationCode code);

    Optional<OrganizationCode> codeOf(Long organizationId);

    void assign(OrganizationCode code, Long organizationId);
}
