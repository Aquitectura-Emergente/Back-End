package com.emsafe.personaldosimetry.application;

public interface DosimetryDirectory {
    /**
     * {@code worker} y {@code ssoResponsible} son los roles de IAM del segmento
     * industrial (WORKER, SSO_RESPONSIBLE): las personas reales de la planta, que
     * hasta el 2026-11-24 no podían recibir membership por no ser CLIENT.
     */
    record User(Long id, boolean active, boolean administrator, boolean clientAccount, boolean company,
                boolean worker, boolean ssoResponsible) {}
    record Device(Long id, Long organizationId, boolean active) {}

    User userByEmail(String email);
    User userById(Long id);
    Device deviceById(Long id);
}
