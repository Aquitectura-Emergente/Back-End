package com.emsafe.devicemanagement.application.ports;

import com.emsafe.iam.domain.model.AppUser;

import java.util.Optional;

/** Cuentas de IAM que Device Management necesita como dueñas de dispositivos. */
public interface AccountDirectory {
    Optional<AppUser> findById(Long id);

    Optional<AppUser> findByEmail(String email);
}
