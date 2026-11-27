package com.emsafe.personaldosimetry.application;

import java.util.List;
import java.util.Optional;

public interface DosimetryDirectory {
    /**
     * {@code worker} y {@code ssoResponsible} son los roles de IAM del segmento
     * industrial (WORKER, SSO_RESPONSIBLE): las personas reales de la planta, que
     * hasta el 2026-11-24 no podían recibir membership por no ser CLIENT.
     */
    record User(Long id, boolean active, boolean administrator, boolean clientAccount, boolean company,
                boolean worker, boolean ssoResponsible) {}
    record Device(Long id, Long organizationId, boolean active) {}
    /** Cuenta WORKER ({@code worker}) o SSO_RESPONSIBLE ligada a una organización. */
    record IndustrialAccount(Long id, Long organizationId, boolean worker, boolean active) {}

    User userByEmail(String email);
    User userById(Long id);
    Device deviceById(Long id);

    /** Operarios y responsables de SSO con organización, para completar sus memberships. */
    List<IndustrialAccount> industrialAccounts();

    /** Un administrador activo, para firmar las memberships que concede el sistema. */
    Optional<Long> anyAdministratorId();
}
