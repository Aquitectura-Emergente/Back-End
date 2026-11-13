package com.emsafe.personaldosimetry.application;

public interface DosimetryDirectory {
    record User(Long id, boolean active, boolean administrator, boolean clientAccount, boolean company) {}
    record Device(Long id, Long organizationId, boolean active) {}

    User userByEmail(String email);
    User userById(Long id);
    Device deviceById(Long id);
}
