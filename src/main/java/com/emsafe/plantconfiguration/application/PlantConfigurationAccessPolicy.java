package com.emsafe.plantconfiguration.application;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Quién puede configurar una planta.
 *
 * <p>{@code SecurityConfig} cierra la puerta del contexto por rol; esta política
 * hace el filtrado fino que una regla de URL no puede expresar: que el SSO solo
 * toque <b>su</b> organización.
 *
 * <p>Sigue el mismo criterio que {@code MitigationAccessPolicy}: una cuenta sin
 * organización no ve nada industrial, ni siquiera siendo ADMIN, porque sin
 * organización no hay forma de acotar a qué planta pertenece el dato.
 *
 * <p>Diferencia deliberada con ese contexto: aquí el <b>ADMIN sí puede escribir</b>.
 * En mitigación el ADMIN queda fuera de decidir porque la confirmación humana de
 * una medida es una firma con consecuencias legales; dar de alta una zona o un
 * puesto es administración de catálogo, no una decisión sobre la operación.
 */
@Component
@RequiredArgsConstructor
public class PlantConfigurationAccessPolicy {

    private final UserRepository userRepository;

    public AppUser currentUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new AccessDeniedException("Access denied");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
    }

    /**
     * Organización sobre la que opera quien llama. Exige rol SSO o ADMIN y una
     * organización asignada.
     */
    public Long assertCanConfigure(Authentication auth) {
        AppUser user = currentUser(auth);
        if (!user.hasRole(Role.SSO_RESPONSIBLE) && !user.hasRole(Role.ADMIN)) {
            throw new AccessDeniedException(
                    "Only an occupational safety officer can configure a plant");
        }
        return organizationOf(user);
    }

    /** Lectura: SSO, ADMIN y el propio operario de la planta. */
    public Long assertCanRead(Authentication auth) {
        AppUser user = currentUser(auth);
        if (!user.hasRole(Role.SSO_RESPONSIBLE)
                && !user.hasRole(Role.ADMIN)
                && !user.hasRole(Role.WORKER)) {
            throw new AccessDeniedException("Access denied");
        }
        return organizationOf(user);
    }

    private Long organizationOf(AppUser user) {
        if (user.getOrganizationId() == null) {
            throw new AccessDeniedException(
                    "This account is not assigned to an organization");
        }
        return user.getOrganizationId();
    }
}
