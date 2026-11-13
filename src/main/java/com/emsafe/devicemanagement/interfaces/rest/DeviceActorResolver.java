package com.emsafe.devicemanagement.interfaces.rest;

import com.emsafe.devicemanagement.application.DeviceActor;
import com.emsafe.devicemanagement.application.ports.AccountDirectory;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Traduce el usuario autenticado al actor de Device Management. */
@Component
@RequiredArgsConstructor
public class DeviceActorResolver {
    private final AccountDirectory accounts;

    public DeviceActor resolve(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new AccessDeniedException("Authentication required");
        AppUser user = accounts.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Authenticated account not found"));
        return new DeviceActor(user.getId(), user.getEmail(), user.hasRole(Role.ADMIN),
                user.hasRole(Role.SSO_RESPONSIBLE), user.hasRole(Role.CLIENT), user.getOrganizationId());
    }
}
