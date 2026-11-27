package com.emsafe.aiforecasting.infrastructure;

import com.emsafe.aiforecasting.application.ForecastingActor;
import com.emsafe.aiforecasting.application.ForecastingActorDirectory;
import com.emsafe.iam.domain.model.AppUser;
import com.emsafe.iam.domain.model.Role;
import com.emsafe.iam.infrastructure.persistence.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Directorio sobre IAM (solo lectura). Traduce una cuenta a la organización que representa:
 * <ul>
 *   <li>ADMIN: administrador, sin organización (ve todas).</li>
 *   <li>CLIENT con clientType=company: la organización es el id del propio usuario.</li>
 *   <li>SSO_RESPONSIBLE: la organización es {@code users.organization_id} (docs/iam.md §3).
 *       Si está en null, no representa a ninguna y la política responde 403.</li>
 *   <li>Cualquier otro rol (WORKER, TECHNICIAN, CLIENT individual): ninguna organización, así
 *       que no accede a las consultas. El WORKER tiene organización en IAM, pero aquí no se
 *       usa a propósito: las evaluaciones son de la organización, no del operario.</li>
 * </ul>
 */
@Component
public class IamForecastingActorDirectory implements ForecastingActorDirectory {

    private static final String COMPANY = "company";

    private final UserRepository users;

    public IamForecastingActorDirectory(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ForecastingActor> findByUsername(String username) {
        return users.findByEmail(username).map(IamForecastingActorDirectory::toActor);
    }

    static ForecastingActor toActor(AppUser user) {
        boolean admin = user.hasRole(Role.ADMIN);
        return new ForecastingActor(user.getId(), admin, organizationOf(user));
    }

    private static Long organizationOf(AppUser user) {
        if (user.hasRole(Role.CLIENT) && COMPANY.equalsIgnoreCase(user.getClientType())) {
            return user.getId();
        }
        if (user.hasRole(Role.SSO_RESPONSIBLE)) {
            return user.getOrganizationId();
        }
        return null;
    }
}
