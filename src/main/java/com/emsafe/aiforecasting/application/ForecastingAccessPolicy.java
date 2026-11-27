package com.emsafe.aiforecasting.application;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Objects;

/**
 * Único punto de control de acceso del contexto AI Forecasting.
 *
 * <ul>
 *   <li>Inbox y outbox: solo ADMIN.</li>
 *   <li>Consultas: ADMIN (todas las organizaciones), o un actor que representa a una organización
 *       (cuenta CLIENT company o SSO_RESPONSIBLE de esa organización) sobre sus propios datos.</li>
 *   <li>WORKER, TECHNICIAN, CLIENT individual y SSO sin organización: 403.</li>
 * </ul>
 * La política no mira el rol: decide con {@link ForecastingActor#organizationId()}, que fija
 * el directorio de actores. Toda denegación es {@link AccessDeniedException} → 403 (también sin
 * token, como el resto del proyecto).
 */
public class ForecastingAccessPolicy {

    private final ForecastingActorDirectory directory;

    public ForecastingAccessPolicy(ForecastingActorDirectory directory) {
        this.directory = directory;
    }

    public ForecastingActor actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("Authentication is required");
        }
        return directory.findByUsername(authentication.getName())
                .orElseThrow(() -> new AccessDeniedException("Unknown user"));
    }

    public void requireAdministrator(ForecastingActor actor) {
        if (!actor.administrator()) {
            throw new AccessDeniedException("Administrator access is required");
        }
    }

    public void requireOrganizationAccess(ForecastingActor actor, Long organizationId) {
        if (actor.administrator()) {
            return;
        }
        if (actor.organizationId() == null || !Objects.equals(actor.organizationId(), organizationId)) {
            throw new AccessDeniedException("Organization is outside the authorized scope");
        }
    }

    /**
     * Organización efectiva de una consulta. ADMIN puede no filtrar ({@code null}); una
     * organización dueña solo ve la suya y, si no la indica, se usa la suya.
     */
    public Long organizationScope(ForecastingActor actor, Long requestedOrganizationId) {
        if (actor.administrator()) {
            return requestedOrganizationId;
        }
        if (actor.organizationId() == null) {
            throw new AccessDeniedException("Organization access is required");
        }
        if (requestedOrganizationId != null) {
            requireOrganizationAccess(actor, requestedOrganizationId);
        }
        return actor.organizationId();
    }
}
