package com.emsafe.devicemanagement.application;

import org.springframework.security.access.AccessDeniedException;

import java.util.Objects;

/**
 * Quién pide una operación. {@code organizationId} es la organización del
 * responsable de SSO; para un cliente, su propia cuenta.
 */
public record DeviceActor(Long userId, String name, boolean administrator, boolean ssoResponsible,
                          boolean client, Long organizationId) {

    public static DeviceActor system(String name) {
        return new DeviceActor(null, name, true, false, false, null);
    }

    public void requireAdministrator() {
        if (!administrator) throw new AccessDeniedException("Administrator access is required");
    }

    /** Responsable de SSO de la organización dueña (o administrador). */
    public void requireSso(Long deviceOrganization) {
        if (administrator) return;
        if (!ssoResponsible || deviceOrganization == null || !deviceOrganization.equals(organizationId)) {
            throw new AccessDeniedException("SSO access to this device's organization is required");
        }
    }

    /** Cliente dueño del dispositivo (o administrador). */
    public void requireOwner(Long deviceOrganization) {
        if (administrator) return;
        if (!client || deviceOrganization == null || !deviceOrganization.equals(userId)) {
            throw new AccessDeniedException("Only the owner of this device can do that");
        }
    }

    public void requireRead(Long deviceOrganization) {
        if (administrator) return;
        boolean sso = ssoResponsible && deviceOrganization != null && deviceOrganization.equals(organizationId);
        boolean owner = client && deviceOrganization != null && deviceOrganization.equals(userId);
        if (!sso && !owner) throw new AccessDeniedException("This device is outside the authorized scope");
    }

    public String label() {
        return Objects.requireNonNullElse(name, "system");
    }
}
