package com.emsafe.occupationalhealth.application;

import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import com.emsafe.occupationalhealth.domain.model.MitigationAuditEntry;
import com.emsafe.occupationalhealth.infrastructure.MitigationAuditAdapter;
import com.emsafe.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Quién puede ver y decidir qué (informe §5.6.0: RF03, RNF10 y RNF11).
 *
 * SecurityConfig ya cierra la puerta del contexto por rol; esta política hace el
 * filtrado fino que una regla de URL no puede expresar: que el SSO sea de la
 * misma organización que el dato, y que un operario solo vea lo suyo.
 *
 * Una cuenta sin organización no ve nada industrial, ni siquiera siendo ADMIN.
 * Es deliberado: sin organización no hay forma de acotar a qué planta pertenece
 * el dato, y devolver todo sería justo el cruce entre organizaciones que RNF11
 * prohíbe. Un administrador que deba auditar se asigna a una organización.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MitigationAccessPolicy {

    private static final String RESOURCE = "OccupationalHealth";

    private final UserRepository userRepository;
    private final MitigationAuditAdapter audit;

    public AppUser currentUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new AccessDeniedException("Access denied");
        }
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
    }

    /** La organización del usuario que llama. Falla si no tiene ninguna. */
    public Long organizationOf(AppUser user) {
        if (user.getOrganizationId() == null) {
            deny(null, user.getId(), MitigationAuditEntry.ACTION_READ,
                    "Account has no organization assigned");
            throw new AccessDeniedException(
                    "This account is not assigned to an organization");
        }
        return user.getOrganizationId();
    }

    /**
     * Solo un Responsable de SSO decide medidas (regla 1). El ADMIN queda fuera
     * a propósito: la regla del informe nombra al SSO, y dejar que un
     * administrador apruebe convertiría la confirmación humana en un trámite que
     * cualquiera con permisos puede firmar.
     */
    public AppUser assertCanDecide(Authentication auth) {
        AppUser user = currentUser(auth);
        if (!user.hasRole(Role.SSO)) {
            deny(user.getOrganizationId(), user.getId(), MitigationAuditEntry.ACTION_APPROVE,
                    "Role " + user.getRole() + " cannot decide mitigation actions");
            throw new AccessDeniedException(
                    "Only an occupational safety officer can decide a mitigation action");
        }
        organizationOf(user);
        return user;
    }

    /** Leer la bandeja y el historial: SSO o ADMIN de la organización. */
    public AppUser assertCanRead(Authentication auth) {
        AppUser user = currentUser(auth);
        if (!user.hasRole(Role.SSO) && !user.hasRole(Role.ADMIN)) {
            deny(user.getOrganizationId(), user.getId(), MitigationAuditEntry.ACTION_READ,
                    "Role " + user.getRole() + " cannot read mitigation data");
            throw new AccessDeniedException("Access denied");
        }
        organizationOf(user);
        return user;
    }

    /**
     * Consultar los turnos de un operario: el SSO y el ADMIN de su organización,
     * o el propio operario.
     */
    public AppUser assertCanSeeWorker(Authentication auth, Long workerId) {
        AppUser user = currentUser(auth);
        boolean isSelf = user.hasRole(Role.WORKER) && user.getId().equals(workerId);
        if (!isSelf && !user.hasRole(Role.SSO) && !user.hasRole(Role.ADMIN)) {
            deny(user.getOrganizationId(), user.getId(), MitigationAuditEntry.ACTION_READ,
                    "Worker " + user.getId() + " tried to read data of worker " + workerId);
            throw new AccessDeniedException("Access denied");
        }
        organizationOf(user);
        return user;
    }

    /**
     * Comprueba que el dato pertenezca a la organización de quien pregunta.
     * Se lanza 404 y no 403 a propósito: un recurso de otra organización no debe
     * ni confirmarse que exista.
     */
    public void assertSameOrganization(AppUser user, Long resourceOrganizationId,
                                       String resource, Long resourceId) {
        if (!user.belongsTo(resourceOrganizationId)) {
            deny(user.getOrganizationId(), user.getId(), MitigationAuditEntry.ACTION_READ,
                    "Cross-organization access to " + resource + " " + resourceId);
            throw new ResourceNotFoundException(resource, resourceId);
        }
    }

    /**
     * Deja constancia del intento denegado. No deja que un fallo al auditar
     * convierta un 403 en un 500: la denegación es lo importante, y perder la
     * línea de auditoría se registra en el log pero no cambia la respuesta.
     */
    private void deny(Long organizationId, Long actorId, String action, String reason) {
        try {
            audit.recordDenied(organizationId, actorId, RESOURCE, null, action, reason);
        } catch (RuntimeException ex) {
            log.error("Could not write the audit entry for a denied access", ex);
        }
    }
}
