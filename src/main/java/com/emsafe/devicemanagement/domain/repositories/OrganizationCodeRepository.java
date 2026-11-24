package com.emsafe.devicemanagement.domain.repositories;

import com.emsafe.devicemanagement.domain.model.OrganizationCode;

import java.util.Map;
import java.util.Optional;

/** Traduce el código que envían los Edges al id numérico de la organización. */
public interface OrganizationCodeRepository {
    Optional<Long> resolve(OrganizationCode code);

    Optional<OrganizationCode> codeOf(Long organizationId);

    void assign(OrganizationCode code, Long organizationId);

    /** Código → organización, ordenado por código. */
    Map<String, Long> findAll();
}
